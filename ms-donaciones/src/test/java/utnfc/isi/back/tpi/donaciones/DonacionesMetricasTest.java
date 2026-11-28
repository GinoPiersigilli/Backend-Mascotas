package utnfc.isi.back.tpi.donaciones;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import utnfc.isi.back.tpi.donaciones.client.MercadoPagoClient;
import utnfc.isi.back.tpi.donaciones.repository.DonacionRepository;
import utnfc.isi.back.tpi.donaciones.service.VerificadorFirmaMercadoPago;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica /actuator/prometheus protegido con rol SERVICIO, graceful shutdown y
 * contadores de negocio (creadas, confirmadas y webhooks) en ms-donaciones.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
@Tag("integration")
class DonacionesMetricasTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Environment env;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private DonacionRepository donacionRepository;

    @MockitoBean
    private MercadoPagoClient mercadoPago;

    private static RequestPostProcessor con(String rol) {
        return jwt().jwt(j -> j.subject("u-" + rol)).authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    @BeforeEach
    void setUp() {
        donacionRepository.deleteAll();
        reset(mercadoPago);
    }

    @Test
    void actuatorPrometheusRequiereRolServicio() throws Exception {
        assertThat(env.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(env.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("20s");

        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(con("ADOPTANTE"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(con("COORDINADOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(con("SERVICIO")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("application=\"ms-donaciones\"")))
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
    }

    @Test
    void registraContadoresDeDonacionesYWebhooksYLosExponeEnPrometheus() throws Exception {
        double creadasAntes = meterRegistry.counter("donaciones.creadas").count();
        double confirmadasAntes = meterRegistry.counter("donaciones.confirmadas").count();
        double webhooksRecibidosAntes = meterRegistry.counter("donaciones.webhooks.recibidos").count();
        double webhooksRechazadosAntes = meterRegistry.counter("donaciones.webhooks.rechazados").count();

        when(mercadoPago.crearCobro(anyString(), any(), anyString()))
                .thenReturn(new MercadoPagoClient.Cobro("pref-met-1", "https://mp.test/met-1"));

        String body = mvc.perform(post("/api/donaciones").with(con("ADOPTANTE"))
                        .header("Idempotency-Key", "met-idem-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monto\":500}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();

        // Reintento idempotente no vuelve a incrementar donaciones.creadas
        mvc.perform(post("/api/donaciones").with(con("ADOPTANTE"))
                        .header("Idempotency-Key", "met-idem-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monto\":500}"))
                .andExpect(status().isCreated());

        assertThat(meterRegistry.counter("donaciones.creadas").count()).isEqualTo(creadasAntes + 1.0);
        assertThat(meterRegistry.counter("donaciones.confirmadas").count()).isEqualTo(confirmadasAntes);

        // Confirmacion en consulta bajo demanda incrementa donaciones.confirmadas una sola vez
        when(mercadoPago.buscarPagoAprobado(anyString())).thenReturn(Optional.of("pago-met-1"));
        mvc.perform(get("/api/donaciones/" + id).with(con("ADOPTANTE")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/donaciones/" + id).with(con("ADOPTANTE")))
                .andExpect(status().isOk());
        assertThat(meterRegistry.counter("donaciones.confirmadas").count()).isEqualTo(confirmadasAntes + 1.0);

        // Webhook con firma invalida -> rechazados +1
        mvc.perform(post("/api/donaciones/webhooks/mercadopago")
                        .header("x-signature", "ts=1704908010,v1=firma-invalida")
                        .header("x-request-id", "req-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"payment\",\"data\":{\"id\":\"9001\"}}"))
                .andExpect(status().isUnauthorized());
        assertThat(meterRegistry.counter("donaciones.webhooks.rechazados").count())
                .isEqualTo(webhooksRechazadosAntes + 1.0);

        // Webhook con firma valida (y confirmacion de una segunda donacion)
        when(mercadoPago.crearCobro(anyString(), any(), anyString()))
                .thenReturn(new MercadoPagoClient.Cobro("pref-met-2", "https://mp.test/met-2"));
        String body2 = mvc.perform(post("/api/donaciones").with(con("ADOPTANTE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monto\":750}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id2 = ((Number) JsonPath.read(body2, "$.id")).longValue();
        String referencia2 = donacionRepository.findById(id2).orElseThrow().getReferencia();
        when(mercadoPago.obtenerPago("9002")).thenReturn(
                Optional.of(new MercadoPagoClient.PagoDetalle("9002", "approved", referencia2)));

        String ts = "1704908010";
        String reqId = "req-ok-9002";
        String firma = VerificadorFirmaMercadoPago.firmar("test-webhook-secret", "9002", reqId, ts);
        mvc.perform(post("/api/donaciones/webhooks/mercadopago")
                        .header("x-signature", "ts=" + ts + ",v1=" + firma)
                        .header("x-request-id", reqId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"payment\",\"data\":{\"id\":\"9002\"}}"))
                .andExpect(status().isOk());

        assertThat(meterRegistry.counter("donaciones.creadas").count()).isEqualTo(creadasAntes + 2.0);
        assertThat(meterRegistry.counter("donaciones.confirmadas").count()).isEqualTo(confirmadasAntes + 2.0);
        assertThat(meterRegistry.counter("donaciones.webhooks.recibidos").count())
                .isEqualTo(webhooksRecibidosAntes + 1.0);

        // Scrape Prometheus expone los cuatro contadores de negocio
        mvc.perform(get("/actuator/prometheus").with(con("SERVICIO")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("donaciones_creadas_total")))
                .andExpect(content().string(containsString("donaciones_confirmadas_total")))
                .andExpect(content().string(containsString("donaciones_webhooks_recibidos_total")))
                .andExpect(content().string(containsString("donaciones_webhooks_rechazados_total")));
    }
}

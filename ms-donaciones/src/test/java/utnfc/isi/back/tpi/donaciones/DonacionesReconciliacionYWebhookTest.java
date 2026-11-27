package utnfc.isi.back.tpi.donaciones;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import utnfc.isi.back.tpi.common.exception.ServicioNoDisponibleException;
import utnfc.isi.back.tpi.donaciones.client.MercadoPagoClient;
import utnfc.isi.back.tpi.donaciones.client.MercadoPagoClient.PagoDetalle;
import utnfc.isi.back.tpi.donaciones.model.Donacion;
import utnfc.isi.back.tpi.donaciones.model.EstadoDonacion;
import utnfc.isi.back.tpi.donaciones.repository.DonacionRepository;
import utnfc.isi.back.tpi.donaciones.service.DonacionService;
import utnfc.isi.back.tpi.donaciones.service.TareaReconciliacionDonaciones;
import utnfc.isi.back.tpi.donaciones.service.VerificadorFirmaMercadoPago;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de F2:
 * - Webhook firmado de Mercado Pago (x-signature HMAC-SHA256, consulta autoritativa, idempotencia, 401/503).
 * - Reconciliacion programada de donaciones PENDIENTE antiguas (antiguedad minima, uk_donacion_pago_id, metricas).
 * - Contrato HTTP de MercadoPagoClient.obtenerPago.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DonacionesReconciliacionYWebhookTest {

    private static final String SECRETO_TEST = "test-webhook-secret";
    private static final Instant AHORA = Instant.parse("2026-06-01T12:00:00Z");

    @TestConfiguration
    static class RelojFijoConfig {
        @Bean
        @Primary
        Clock clockFijo() {
            return Clock.fixed(AHORA, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private DonacionRepository repository;
    @Autowired
    private DonacionService donacionService;

    @MockitoBean
    private MercadoPagoClient mercadoPago;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        reset(mercadoPago);
    }

    @Test
    void webhookRechazaFirmaAusenteOInvalidaCon401() throws Exception {
        mvc.perform(post("/api/donaciones/webhooks/mercadopago")
                        .param("type", "payment")
                        .param("data.id", "999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"payment\",\"data\":{\"id\":\"999\"}}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("No autenticado"));

        mvc.perform(post("/api/donaciones/webhooks/mercadopago")
                        .param("type", "payment")
                        .param("data.id", "999")
                        .header("x-request-id", "req-1")
                        .header("x-signature", "ts=1704908010,v1=deadbeef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"payment\",\"data\":{\"id\":\"999\"}}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        verify(mercadoPago, never()).obtenerPago(anyString());
    }

    @Test
    void webhookValidoConfirmaDonacionIdempotentemente() throws Exception {
        Donacion guardada = repository.saveAndFlush(new Donacion(
                "ref-wh-1", "donante-1", new BigDecimal("750.00"), "pref-1",
                "https://mp.test/pay/1", LocalDateTime.ofInstant(AHORA, ZoneOffset.UTC)));
        when(mercadoPago.obtenerPago("999888777"))
                .thenReturn(Optional.of(new PagoDetalle("999888777", "approved", "ref-wh-1")));

        String ts = "1704908010";
        String reqId = "req-abc-1";
        String v1 = VerificadorFirmaMercadoPago.firmar(SECRETO_TEST, "999888777", reqId, ts);
        String xSignature = "ts=" + ts + ",v1=" + v1;

        mvc.perform(post("/api/donaciones/webhooks/mercadopago")
                        .param("type", "payment")
                        .param("data.id", "999888777")
                        .header("x-request-id", reqId)
                        .header("x-signature", xSignature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"payment\",\"action\":\"payment.updated\",\"data\":{\"id\":\"999888777\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmada").value(true))
                .andExpect(jsonPath("$.resultado").value("CONFIRMADA"));

        Donacion confirmada = repository.findById(guardada.getId()).orElseThrow();
        assertThat(confirmada.getEstado()).isEqualTo(EstadoDonacion.CONFIRMADA);
        assertThat(confirmada.getPagoId()).isEqualTo("999888777");

        // Reenvio duplicado del mismo webhook -> 200 idempotente sin cambios
        mvc.perform(post("/api/donaciones/webhooks/mercadopago")
                        .param("type", "payment")
                        .param("data.id", "999888777")
                        .header("x-request-id", reqId)
                        .header("x-signature", xSignature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"payment\",\"action\":\"payment.updated\",\"data\":{\"id\":\"999888777\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmada").value(false))
                .andExpect(jsonPath("$.resultado").value("SIN_CAMBIOS"));
    }

    @Test
    void webhookIgnoraEventosNoRelacionadosAPagosYDevuelve503SiMpNoResponde() throws Exception {
        String ts = "1704908010";
        String v1Otros = VerificadorFirmaMercadoPago.firmar(SECRETO_TEST, "order-1", "req-2", ts);
        mvc.perform(post("/api/donaciones/webhooks/mercadopago")
                        .param("type", "merchant_order")
                        .param("data.id", "order-1")
                        .header("x-request-id", "req-2")
                        .header("x-signature", "ts=" + ts + ",v1=" + v1Otros))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultado").value("IGNORADO"));

        String v1Caido = VerificadorFirmaMercadoPago.firmar(SECRETO_TEST, "555", "req-3", ts);
        when(mercadoPago.obtenerPago("555")).thenThrow(new ServicioNoDisponibleException("MP caido"));
        mvc.perform(post("/api/donaciones/webhooks/mercadopago")
                        .param("type", "payment")
                        .param("data.id", "555")
                        .header("x-request-id", "req-3")
                        .header("x-signature", "ts=" + ts + ",v1=" + v1Caido))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void tareaReconciliacionConfirmaAntiguasRespetaUnicidadYOmiteRecientes() {
        LocalDateTime ahora = LocalDateTime.ofInstant(AHORA, ZoneOffset.UTC);
        Donacion antigua1 = repository.saveAndFlush(new Donacion(
                "ref-old-1", "d-1", new BigDecimal("100.00"), "p-1", "https://mp.test/1", ahora.minusMinutes(10)));
        Donacion antigua2 = repository.saveAndFlush(new Donacion(
                "ref-old-2", "d-2", new BigDecimal("200.00"), "p-2", "https://mp.test/2", ahora.minusMinutes(8)));
        Donacion reciente = repository.saveAndFlush(new Donacion(
                "ref-new-1", "d-3", new BigDecimal("300.00"), "p-3", "https://mp.test/3", ahora.minusMinutes(1)));

        when(mercadoPago.buscarPagoAprobado("ref-old-1")).thenReturn(Optional.of("pay-dup-1"));
        // Mismo pago_id para la segunda donacion: uk_donacion_pago_id debe mantenerla PENDIENTE sin fallar el lote
        when(mercadoPago.buscarPagoAprobado("ref-old-2")).thenReturn(Optional.of("pay-dup-1"));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TareaReconciliacionDonaciones tarea = new TareaReconciliacionDonaciones(donacionService, registry);
        tarea.reconciliarPendientes();

        assertThat(registry.counter("donaciones.reconciliacion.confirmadas").count()).isEqualTo(1.0);
        assertThat(registry.counter("donaciones.reconciliacion.fallidas").count()).isEqualTo(0.0);
        assertThat(repository.findById(antigua1.getId()).orElseThrow().getEstado()).isEqualTo(EstadoDonacion.CONFIRMADA);
        assertThat(repository.findById(antigua2.getId()).orElseThrow().getEstado()).isEqualTo(EstadoDonacion.PENDIENTE);
        assertThat(repository.findById(reciente.getId()).orElseThrow().getEstado()).isEqualTo(EstadoDonacion.PENDIENTE);
        verify(mercadoPago, never()).buscarPagoAprobado("ref-new-1");
    }

    @Test
    void mercadoPagoClientObtenerPagoParsea200YDevuelveVacioEn404() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.mercadopago.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MercadoPagoClient client = new MercadoPagoClient(builder.build(), false);

        server.expect(requestTo("https://api.mercadopago.test/v1/payments/777"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"id\":777,\"status\":\"approved\",\"external_reference\":\"ref-777\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.mercadopago.test/v1/payments/404"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withResourceNotFound());

        Optional<PagoDetalle> encontrado = client.obtenerPago("777");
        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().aprobado()).isTrue();
        assertThat(encontrado.get().externalReference()).isEqualTo("ref-777");
        assertThat(client.obtenerPago("404")).isEmpty();
        server.verify();
    }
}

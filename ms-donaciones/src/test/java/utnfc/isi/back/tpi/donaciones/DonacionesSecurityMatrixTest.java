package utnfc.isi.back.tpi.donaciones;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import utnfc.isi.back.tpi.donaciones.client.MercadoPagoClient;
import utnfc.isi.back.tpi.donaciones.model.Donacion;
import utnfc.isi.back.tpi.donaciones.repository.DonacionRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Matriz de seguridad negativa de ms-donaciones (WS-5): IDOR, RBAC cruzado y probes Actuator.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
class DonacionesSecurityMatrixTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private DonacionRepository repository;

    @MockitoBean
    private MercadoPagoClient mercadoPago;

    private static RequestPostProcessor con(String sub, String rol) {
        return jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void idorYMatrizRbacNegativaEnDonaciones() throws Exception {
        Donacion ajena = repository.saveAndFlush(new Donacion(
                "ref-sec-1", "donante-duenio", new BigDecimal("500.00"), "pref-1",
                "https://mp.test/1", LocalDateTime.of(2026, 6, 1, 10, 0)));

        // Sin token -> 401
        mvc.perform(get("/api/donaciones")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/donaciones/total")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/donaciones").contentType(MediaType.APPLICATION_JSON).content("{\"monto\":500}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());

        // IDOR: otro adoptante intenta ver donacion ajena -> 403
        mvc.perform(get("/api/donaciones/{id}", ajena.getId()).with(con("donante-intruso", "ADOPTANTE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // ADOPTANTE sobre /total o /actuator/prometheus -> 403
        mvc.perform(get("/api/donaciones/total").with(con("donante-duenio", "ADOPTANTE"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(con("donante-duenio", "ADOPTANTE"))).andExpect(status().isForbidden());

        // COORDINADOR intentando crear donacion o leer prometheus -> 403
        mvc.perform(post("/api/donaciones").with(con("coord-1", "COORDINADOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"monto\":500}")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(con("coord-1", "COORDINADOR"))).andExpect(status().isForbidden());

        // SERVICIO intentando la API publica -> 403
        mvc.perform(get("/api/donaciones").with(con("svc", "SERVICIO"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/donaciones/total").with(con("svc", "SERVICIO"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/donaciones").with(con("svc", "SERVICIO"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"monto\":500}")).andExpect(status().isForbidden());
    }

    @Test
    void probesLivenessYReadinessRespondenUpSinExponerDetallesInternos() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}

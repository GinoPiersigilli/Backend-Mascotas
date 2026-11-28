package utnfc.isi.back.tpi.animales;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Matriz de seguridad negativa de ms-animales (WS-5): RBAC completo y probes Actuator.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
class AnimalesSecurityMatrixTest {

    @Autowired
    private MockMvc mvc;

    private static final String ANIMAL_JSON = """
            {"nombre":"Luna","especie":"PERRO","edadMeses":24,"tamanio":"MEDIANO",
             "aptoNinios":true,"aptoOtrosAnimales":true,"requiereEspacioExterior":false}""";

    private static RequestPostProcessor con(String rol) {
        return jwt().jwt(j -> j.subject("u-" + rol)).authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    @Test
    void matrizRbacNegativaEnRutasPublicasInternasYMetricas() throws Exception {
        // Sin token -> 401
        mvc.perform(get("/api/animales")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/animales/1")).andExpect(status().isUnauthorized());
        mvc.perform(put("/internal/animales/1/estado").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nuevoEstado\":\"RESERVADO\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());

        // ADOPTANTE no puede modificar animales, acceder a /internal ni a /actuator/prometheus -> 403
        mvc.perform(put("/api/animales/1").with(con("ADOPTANTE"))
                .contentType(MediaType.APPLICATION_JSON).content(ANIMAL_JSON)).andExpect(status().isForbidden());
        mvc.perform(post("/api/animales/1/marcar-no-disponible").with(con("ADOPTANTE"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/animales/1/marcar-disponible").with(con("ADOPTANTE"))).andExpect(status().isForbidden());
        mvc.perform(get("/internal/animales/1").with(con("ADOPTANTE"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(con("ADOPTANTE"))).andExpect(status().isForbidden());

        // COORDINADOR no puede acceder a /internal ni a /actuator/prometheus -> 403
        mvc.perform(get("/internal/animales/1").with(con("COORDINADOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(con("COORDINADOR"))).andExpect(status().isForbidden());

        // SERVICIO no puede acceder a la API publica -> 403
        mvc.perform(get("/api/animales/1").with(con("SERVICIO"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/animales").with(con("SERVICIO"))
                .contentType(MediaType.APPLICATION_JSON).content(ANIMAL_JSON)).andExpect(status().isForbidden());
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

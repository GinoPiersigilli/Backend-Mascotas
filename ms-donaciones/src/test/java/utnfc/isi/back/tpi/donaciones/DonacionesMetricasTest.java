package utnfc.isi.back.tpi.donaciones;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifica que /actuator/prometheus esta expuesto y protegido con rol SERVICIO en ms-donaciones. */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
@Tag("integration")
class DonacionesMetricasTest {

    @Autowired
    private MockMvc mvc;

    private static RequestPostProcessor con(String rol) {
        return jwt().jwt(j -> j.subject("u-" + rol)).authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    @Test
    void actuatorPrometheusRequiereRolServicio() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(con("ADOPTANTE"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(con("COORDINADOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(con("SERVICIO")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("application=\"ms-donaciones\"")))
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
    }
}

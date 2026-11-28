package utnfc.isi.back.tpi.animales;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifica /actuator/prometheus protegido con rol SERVICIO y graceful shutdown en ms-animales. */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
@Tag("integration")
class AnimalesMetricasTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Environment env;

    private static RequestPostProcessor con(String rol) {
        return jwt().jwt(j -> j.subject("u-" + rol)).authorities(new SimpleGrantedAuthority("ROLE_" + rol));
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
                .andExpect(content().string(containsString("application=\"ms-animales\"")))
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
    }
}

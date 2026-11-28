package ar.org.refugio.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("unit")
class SecurityCommonTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void jsonAuthenticationEntryPointResponde401ConFormatoUnico() throws Exception {
        JsonAuthenticationEntryPoint entryPoint = new JsonAuthenticationEntryPoint(objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/animales");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("invalido"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        JsonNode json = objectMapper.readTree(response.getContentAsString());
        assertThat(json.get("status").asInt()).isEqualTo(401);
        assertThat(json.get("error").asText()).isEqualTo("No autenticado");
        assertThat(json.get("path").asText()).isEqualTo("/api/animales");
    }

    @Test
    void jsonAccessDeniedHandlerResponde403ConFormatoUnico() throws Exception {
        JsonAccessDeniedHandler handler = new JsonAccessDeniedHandler(objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/animales");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("sin permisos"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        JsonNode json = objectMapper.readTree(response.getContentAsString());
        assertThat(json.get("status").asInt()).isEqualTo(403);
        assertThat(json.get("error").asText()).isEqualTo("Prohibido");
        assertThat(json.get("path").asText()).isEqualTo("/api/animales");
    }

    @Test
    void rolesTieneRolEvaluaAutoridadesYNulos() {
        TestingAuthenticationToken coord = new TestingAuthenticationToken("coord1", "n/a", "ROLE_COORDINADOR");

        assertThat(Roles.tieneRol(coord, Roles.COORDINADOR)).isTrue();
        assertThat(Roles.tieneRol(coord, Roles.ADOPTANTE)).isFalse();
        assertThat(Roles.tieneRol(null, Roles.COORDINADOR)).isFalse();
        assertThat(Roles.tieneRol(coord, null)).isFalse();
    }

    @Test
    void jwtValidationPropertiesAplicaDefaultsYNormaliza() {
        JwtValidationProperties porDefecto = new JwtValidationProperties(null, null, "   ");
        assertThat(porDefecto.clockSkew()).isEqualTo(JwtValidationProperties.CLOCK_SKEW_POR_DEFECTO);
        assertThat(porDefecto.allowedClients()).isEmpty();
        assertThat(porDefecto.requiredAudience()).isNull();

        JwtValidationProperties personalizadas = new JwtValidationProperties(
                Duration.ofSeconds(15), List.of(" adopciones-cli ", " ", "adopciones-pkce"), " adopciones-api ");
        assertThat(personalizadas.clockSkew()).isEqualTo(Duration.ofSeconds(15));
        assertThat(personalizadas.allowedClients()).containsExactly("adopciones-cli", "adopciones-pkce");
        assertThat(personalizadas.requiredAudience()).isEqualTo("adopciones-api");
    }

    @Test
    void jwtValidationConfigCreaDecoderPerezosoSinConectar() {
        OAuth2ResourceServerProperties oauthProps = new OAuth2ResourceServerProperties();
        oauthProps.getJwt().setJwkSetUri("http://127.0.0.1:1/realms/adopciones/protocol/openid-connect/certs");
        oauthProps.getJwt().setIssuerUri("http://localhost:8180/realms/adopciones");
        JwtValidationProperties jwtProps = new JwtValidationProperties(
                Duration.ofSeconds(30), List.of("adopciones-cli"), null);

        JwtDecoder decoder = new JwtValidationConfig().jwtDecoder(oauthProps, jwtProps);
        assertThat(decoder).isNotNull();
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class SeguridadDePruebaConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }

        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
            return ResourceServerDefaults.aplicar(
                            http,
                            new JsonAuthenticationEntryPoint(mapper),
                            new JsonAccessDeniedHandler(mapper))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .build();
        }
    }

    @Test
    void resourceServerDefaultsConfiguraCadenaStatelessYResponde401Json() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext()) {
            ctx.setServletContext(new MockServletContext());
            ctx.register(SeguridadDePruebaConfig.class);
            ctx.refresh();

            Filter securityFilter = ctx.getBean("springSecurityFilterChain", Filter.class);
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(ctx)
                    .addFilters(securityFilter)
                    .build();

            mvc.perform(get("/api/protegido"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.error").value("No autenticado"));
        }
    }
}

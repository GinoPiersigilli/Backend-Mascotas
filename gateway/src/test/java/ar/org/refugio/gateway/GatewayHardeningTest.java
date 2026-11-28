package ar.org.refugio.gateway;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import ar.org.refugio.gateway.config.GatewayHardeningProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Pruebas de hardening del API Gateway (F6) y exposicion controlada del webhook de Mercado Pago (F2).
 */
@SpringBootTest(classes = GatewayApplication.class)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "ANIMALES_URL=http://127.0.0.1:1",
        "ADOPCIONES_URL=http://127.0.0.1:1",
        "DONACIONES_URL=http://127.0.0.1:1",
        "GATEWAY_CORS_ALLOWED_ORIGINS=https://app.refugio.test",
        "GATEWAY_MAX_BODY_BYTES=1024"
})
@Tag("integration")
class GatewayHardeningTest {

    @Autowired
    private WebTestClient client;
    @Autowired
    private GatewayHardeningProperties props;
    @Autowired
    private Environment env;

    private WebTestClient con(String rol) {
        return client.mutateWith(mockJwt().jwt(j -> j.subject("u-" + rol))
                .authorities(new SimpleGrantedAuthority("ROLE_" + rol)));
    }

    @Test
    void incluyeCabecerasDeSeguridadEnLasRespuestas() {
        assertThat(env.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(env.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("20s");

        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
                .expectHeader().value("Cache-Control", containsString("no-store"))
                .expectHeader().value("Content-Security-Policy", containsString("default-src 'none'"));
    }

    @Test
    void corsPermiteOrigenConfiguradoYRechazaOrigenNoPermitido() {
        assertThat(props.corsAllowedOrigins()).containsExactly("https://app.refugio.test");

        client.options().uri("http://localhost:8080/api/animales")
                .header("Origin", "https://app.refugio.test")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://app.refugio.test");

        client.options().uri("http://localhost:8080/api/animales")
                .header("Origin", "https://malicioso.test")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    @Test
    void rechazaCuerposQueSuperanElLimiteCon413EnFormatoUnico() {
        String cuerpoGrande = "{\"nombre\":\"" + "a".repeat(1500) + "\"}";

        con("COORDINADOR").post().uri("/api/animales")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(cuerpoGrande)
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectHeader().exists("X-Correlation-ID")
                .expectBody()
                .jsonPath("$.status").isEqualTo(413)
                .jsonPath("$.error").isEqualTo("Cuerpo demasiado grande")
                .jsonPath("$.path").isEqualTo("/api/animales");
    }

    @Test
    void webhookMercadoPagoPostEsPublicoEnGatewayPeroGetSigueExigiendoJwt() {
        // Sin JWT: POST al webhook pasa el filtro de seguridad y alcanza el upstream cerrado -> 503 (no 401)
        client.post().uri("/api/donaciones/webhooks/mercadopago")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"type\":\"payment\"}")
                .exchange()
                .expectStatus().isEqualTo(503);

        // GET sobre la misma ruta sigue protegido por JWT -> 401
        client.get().uri("/api/donaciones/webhooks/mercadopago")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}

package utnfc.isi.back.tpi.gateway;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import utnfc.isi.back.tpi.gateway.security.GatewayJwtValidators;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el validador JWT del Gateway (azp, audiencia opcional, emisor y clock skew) y las probes Actuator.
 */
@SpringBootTest(classes = GatewayApplication.class)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "ANIMALES_URL=http://127.0.0.1:1",
        "ADOPCIONES_URL=http://127.0.0.1:1",
        "DONACIONES_URL=http://127.0.0.1:1"
})
@Tag("integration")
class GatewayJwtValidatorTest {

    private static final String ISSUER = "http://localhost:8180/realms/adopciones";

    @Autowired
    private WebTestClient client;

    private static Jwt token(Instant exp, String azp, List<String> aud) {
        return Jwt.withTokenValue("tok")
                .header("alg", "RS256")
                .subject("sub-1")
                .issuer(ISSUER)
                .issuedAt(exp.minusSeconds(300))
                .expiresAt(exp)
                .claim("azp", azp)
                .audience(aud)
                .build();
    }

    @Test
    void rechazaClienteDeServicioEnGatewayYAceptaClientesPublicos() {
        OAuth2TokenValidator<Jwt> validator = GatewayJwtValidators.crear(
                ISSUER, Duration.ofSeconds(30), List.of("tpi-client", "adopciones-pkce"), "adopciones-api");

        assertThat(validator.validate(token(Instant.now().plusSeconds(120), "adopciones-pkce", List.of("adopciones-api"))).hasErrors()).isFalse();
        assertThat(validator.validate(token(Instant.now().plusSeconds(120), "adopciones-service", List.of("adopciones-api"))).hasErrors()).isTrue();
        assertThat(validator.validate(token(Instant.now().plusSeconds(120), "tpi-client", List.of("account"))).hasErrors()).isTrue();
        assertThat(validator.validate(token(Instant.now().minusSeconds(45), "tpi-client", List.of("adopciones-api"))).hasErrors()).isTrue();
    }

    @Test
    void probesLivenessYReadinessRespondenUpSinDetalles() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist();
        client.get().uri("/actuator/health/liveness").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist();
        client.get().uri("/actuator/health/readiness").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist();
    }
}

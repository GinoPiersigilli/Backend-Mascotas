package utnfc.isi.back.tpi.common.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class JwtTokenValidatorsTest {

    private static final String ISSUER = "http://localhost:8180/realms/adopciones";

    private static Jwt token(String issuer, Instant exp, String azp, List<String> aud) {
        Jwt.Builder builder = Jwt.withTokenValue("token-prueba")
                .header("alg", "RS256")
                .subject("sub-1")
                .issuedAt(exp.minusSeconds(600))
                .expiresAt(exp);
        if (issuer != null) {
            builder.issuer(issuer);
        }
        if (azp != null) {
            builder.claim("azp", azp);
        }
        if (aud != null) {
            builder.audience(aud);
        }
        return builder.build();
    }

    @Test
    void aceptaTokenValidoConIssuerAzpYAudiencia() {
        OAuth2TokenValidator<Jwt> validator = JwtTokenValidators.crear(
                ISSUER, Duration.ofSeconds(30), List.of("tpi-client", "adopciones-service"), "adopciones-api");

        Jwt jwt = token(ISSUER, Instant.now().plusSeconds(300), "tpi-client", List.of("account", "adopciones-api"));
        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void rechazaClienteNoAutorizadoEnAzp() {
        OAuth2TokenValidator<Jwt> validator = JwtTokenValidators.crear(
                ISSUER, Duration.ofSeconds(30), List.of("tpi-client"), null);

        Jwt jwt = token(ISSUER, Instant.now().plusSeconds(300), "otro-cliente", List.of("account"));
        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void rechazaAudienciaFaltanteCuandoEsObligatoria() {
        OAuth2TokenValidator<Jwt> validator = JwtTokenValidators.crear(
                ISSUER, Duration.ofSeconds(30), List.of("tpi-client"), "adopciones-api");

        Jwt jwt = token(ISSUER, Instant.now().plusSeconds(300), "tpi-client", List.of("account"));
        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void rechazaEmisorIncorrecto() {
        OAuth2TokenValidator<Jwt> validator = JwtTokenValidators.crear(
                ISSUER, Duration.ofSeconds(30), List.of("tpi-client"), null);

        Jwt jwt = token("http://keycloak:8080/realms/adopciones", Instant.now().plusSeconds(300),
                "tpi-client", List.of("account"));
        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void respetaClockSkewExplicito() {
        OAuth2TokenValidator<Jwt> validator = JwtTokenValidators.crear(
                ISSUER, Duration.ofSeconds(30), List.of("tpi-client"), null);

        // Vencido hace 10s (dentro del clock skew de 30s) -> valido
        Jwt dentroSkew = token(ISSUER, Instant.now().minusSeconds(10), "tpi-client", List.of("account"));
        assertThat(validator.validate(dentroSkew).hasErrors()).isFalse();

        // Vencido hace 45s (fuera del clock skew de 30s) -> rechazado
        Jwt fueraSkew = token(ISSUER, Instant.now().minusSeconds(45), "tpi-client", List.of("account"));
        assertThat(validator.validate(fueraSkew).hasErrors()).isTrue();
    }

    @Test
    void aceptaParametrosOpcionalesNulosYRechazaClaimsFaltantes() {
        OAuth2TokenValidator<Jwt> permisivo = JwtTokenValidators.crear(" ", null, null, " ");
        Jwt sinClaimsOpcionales = token(null, Instant.now().plusSeconds(60), null, null);
        assertThat(permisivo.validate(sinClaimsOpcionales).hasErrors()).isFalse();

        OAuth2TokenValidator<Jwt> estricto = JwtTokenValidators.crear(ISSUER, Duration.ofSeconds(10),
                List.of("tpi-client"), "adopciones-api");
        assertThat(estricto.validate(token(ISSUER, Instant.now().plusSeconds(60), null, List.of("adopciones-api")))
                .hasErrors()).isTrue();
        assertThat(estricto.validate(token(ISSUER, Instant.now().plusSeconds(60), "tpi-client", null))
                .hasErrors()).isTrue();
    }
}

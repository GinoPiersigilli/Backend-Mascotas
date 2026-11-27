package utnfc.isi.back.tpi.gateway.config;

import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Propiedades tipadas de hardening del API Gateway (F6 / WS-5): origenes CORS permitidos,
 * tamanio maximo de cuerpo HTTP y validacion reforzada de JWT (clock skew, azp y aud).
 */
@Validated
@ConfigurationProperties("gateway.seguridad")
public record GatewayHardeningProperties(
        List<String> corsAllowedOrigins,
        @Positive long maxBodyBytes,
        @DurationMin(seconds = 0) Duration jwtClockSkew,
        List<String> jwtAllowedClients,
        String jwtRequiredAudience) {

    public static final Duration CLOCK_SKEW_POR_DEFECTO = Duration.ofSeconds(30);

    public GatewayHardeningProperties {
        corsAllowedOrigins = normalizar(corsAllowedOrigins);
        jwtClockSkew = jwtClockSkew == null ? CLOCK_SKEW_POR_DEFECTO : jwtClockSkew;
        jwtAllowedClients = normalizar(jwtAllowedClients);
        jwtRequiredAudience = (jwtRequiredAudience == null || jwtRequiredAudience.isBlank())
                ? null : jwtRequiredAudience.trim();
    }

    private static List<String> normalizar(List<String> valores) {
        return valores == null
                ? List.of()
                : valores.stream().map(String::trim).filter(s -> !s.isBlank()).toList();
    }
}

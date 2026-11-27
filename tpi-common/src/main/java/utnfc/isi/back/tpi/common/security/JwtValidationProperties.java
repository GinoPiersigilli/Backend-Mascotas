package utnfc.isi.back.tpi.common.security;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Propiedades tipadas para reforzar la validacion de tokens JWT (WS-5):
 * - clockSkew: tolerancia explicita de desfase horario en exp/nbf (por defecto 30s).
 * - allowedClients: clientes Keycloak permitidos en el claim "azp" (si esta vacio, no filtra).
 * - requiredAudience: audiencia opcional obligatoria en el claim "aud" (si esta vacia, no exige).
 */
@Validated
@ConfigurationProperties("seguridad.jwt")
public record JwtValidationProperties(
        @NotNull @DurationMin(seconds = 0) Duration clockSkew,
        List<String> allowedClients,
        String requiredAudience) {

    public static final Duration CLOCK_SKEW_POR_DEFECTO = Duration.ofSeconds(30);

    public JwtValidationProperties {
        clockSkew = clockSkew == null ? CLOCK_SKEW_POR_DEFECTO : clockSkew;
        allowedClients = allowedClients == null
                ? List.of()
                : allowedClients.stream().map(String::trim).filter(s -> !s.isBlank()).toList();
        requiredAudience = (requiredAudience == null || requiredAudience.isBlank()) ? null : requiredAudience.trim();
    }
}

package utnfc.isi.back.tpi.adopciones.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Direcciones, credenciales y tiempos maximos de espera de los sistemas con los que
 * colabora Adopciones: el servicio de Animales, Keycloak (token de servicio) y Georef.
 */
@Validated
@ConfigurationProperties("adopciones.integraciones")
public record IntegracionesProperties(
        @Valid @NotNull Animales animales,
        @Valid @NotNull Keycloak keycloak,
        @Valid @NotNull Georef georef) {

    public record Animales(@NotBlank String baseUrl, @NotNull Duration timeout) {
    }

    /** Cliente confidencial "adopciones-service" con grant client_credentials (rol SERVICIO). */
    public record Keycloak(@NotBlank String tokenUri, @NotBlank String clientId, @NotBlank String clientSecret,
                           @NotNull Duration timeout) {
    }

    public record Georef(@NotBlank String baseUrl, @NotNull Duration timeout) {
    }
}

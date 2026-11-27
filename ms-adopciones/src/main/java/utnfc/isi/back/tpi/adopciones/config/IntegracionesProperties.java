package utnfc.isi.back.tpi.adopciones.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Direcciones, credenciales, timeouts diferenciados (conexion/lectura) y parametros de
 * circuit breaker de los sistemas con los que colabora Adopciones.
 */
@Validated
@ConfigurationProperties("adopciones.integraciones")
public record IntegracionesProperties(
        @Valid @NotNull Animales animales,
        @Valid @NotNull Keycloak keycloak,
        @Valid @NotNull Georef georef) {

    public record Animales(
            @NotBlank String baseUrl,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout,
            @Valid @NotNull Circuito circuitBreaker) {
    }

    /** Cliente confidencial "adopciones-service" con grant client_credentials (rol SERVICIO). */
    public record Keycloak(
            @NotBlank String tokenUri,
            @NotBlank String clientId,
            @NotBlank String clientSecret,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout) {
    }

    public record Georef(
            @NotBlank String baseUrl,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout,
            @Valid @NotNull Circuito circuitBreaker) {
    }

    public record Circuito(
            @Min(1) @Max(100) float failureRateThreshold,
            @Min(1) int slidingWindowSize,
            @Min(1) int minimumNumberOfCalls,
            @NotNull Duration waitDurationInOpenState,
            @Min(1) int permittedNumberOfCallsInHalfOpenState) {
    }
}

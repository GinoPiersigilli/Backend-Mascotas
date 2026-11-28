package ar.org.refugio.adopciones.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Direcciones, credenciales, timeouts diferenciados (conexion/lectura), circuit breakers y
 * politicas de reintento (solo para operaciones GET idempotentes) de los clientes de Adopciones.
 */
@Validated
@ConfigurationProperties("adopciones.integraciones")
public record IntegracionesProperties(
        @Valid @NotNull Animales animales,
        @Valid @NotNull Keycloak keycloak,
        @Valid @NotNull Georef georef) {

    public record Animales(
            @NotBlank String baseUrl,
            @NotNull @DurationMin(millis = 1) Duration connectTimeout,
            @NotNull @DurationMin(millis = 1) Duration readTimeout,
            @Valid @NotNull Circuito circuitBreaker) {
    }

    /** Cliente confidencial "adopciones-service" con grant client_credentials (rol SERVICIO). */
    public record Keycloak(
            @NotBlank String tokenUri,
            @NotBlank String clientId,
            @NotBlank String clientSecret,
            @NotNull @DurationMin(millis = 1) Duration connectTimeout,
            @NotNull @DurationMin(millis = 1) Duration readTimeout) {
    }

    public record Georef(
            @NotBlank String baseUrl,
            @NotNull @DurationMin(millis = 1) Duration connectTimeout,
            @NotNull @DurationMin(millis = 1) Duration readTimeout,
            @Valid @NotNull Circuito circuitBreaker,
            @Valid @NotNull Reintento retry) {
    }

    public record Circuito(
            @Min(1) @Max(100) float failureRateThreshold,
            @Min(1) int slidingWindowSize,
            @Min(1) int minimumNumberOfCalls,
            @NotNull @DurationMin(millis = 1) Duration waitDurationInOpenState,
            @Min(1) int permittedNumberOfCallsInHalfOpenState) {
    }

    public record Reintento(
            @Min(1) @Max(5) int maxAttempts,
            @NotNull @DurationMin(millis = 1) Duration waitDuration) {
    }
}

package utnfc.isi.back.tpi.donaciones.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Politica de donaciones (D-01) y conexion con Mercado Pago (timeouts diferenciados, circuit breaker
 * y retry solo sobre la consulta GET idempotente de pagos).
 * El access token llega solo por variable de entorno (MP_ACCESS_TOKEN) y nunca se registra.
 */
@Validated
@ConfigurationProperties("donaciones")
public record DonacionesProperties(
        @NotNull @Positive BigDecimal montoMinimo,
        @Valid @NotNull MercadoPago mercadoPago,
        @Valid @NotNull Tareas tareas) {

    public record MercadoPago(
            @NotBlank String baseUrl,
            String accessToken,
            String webhookSecret,
            @NotNull @DurationMin(millis = 1) Duration connectTimeout,
            @NotNull @DurationMin(millis = 1) Duration readTimeout,
            boolean usarSandboxInitPoint,
            @NotBlank String tituloItem,
            @Valid @NotNull Circuito circuitBreaker,
            @Valid @NotNull Reintento retry) {
    }

    public record Tareas(
            boolean habilitadas,
            @NotNull @DurationMin(millis = 1) Duration antiguedadMinima) {
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

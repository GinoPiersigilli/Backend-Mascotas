package utnfc.isi.back.tpi.adopciones.config;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Propiedades de observabilidad para metricas agregadas de ms-adopciones (WS-8).
 * cacheTtl evita ejecutar multiples consultas SQL en un mismo scrape de Prometheus
 * sobre todos los estados del gauge de solicitudes.
 */
@Validated
@ConfigurationProperties("adopciones.metricas")
public record MetricasProperties(
        @NotNull @DurationMin(millis = 0) Duration cacheTtl) {

    public MetricasProperties {
        cacheTtl = cacheTtl == null ? Duration.ofSeconds(5) : cacheTtl;
    }
}

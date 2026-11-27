package utnfc.isi.back.tpi.gateway.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Propiedades tipadas de hardening del API Gateway (F6): origenes CORS permitidos
 * y tamanio maximo de cuerpo HTTP.
 */
@Validated
@ConfigurationProperties("gateway.seguridad")
public record GatewayHardeningProperties(
        List<String> corsAllowedOrigins,
        @Positive long maxBodyBytes) {

    public GatewayHardeningProperties {
        corsAllowedOrigins = corsAllowedOrigins == null
                ? List.of()
                : corsAllowedOrigins.stream().map(String::trim).filter(s -> !s.isBlank()).toList();
    }
}

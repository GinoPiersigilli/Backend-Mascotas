package utnfc.isi.back.tpi.gateway.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import utnfc.isi.back.tpi.gateway.config.GatewayHardeningProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Validador JWT del Gateway (espejo reactivo sin depender de tpi-common, ADR-09):
 * valida exp/nbf con clock skew explicito, emisor exacto, cliente autorizado (azp) y audiencia opcional.
 */
public final class GatewayJwtValidators {

    private static final OAuth2Error ERROR_CLIENTE = new OAuth2Error(
            "invalid_token", "El claim azp del token no corresponde a un cliente publico autorizado", null);
    private static final OAuth2Error ERROR_AUDIENCIA = new OAuth2Error(
            "invalid_token", "El token no contiene la audiencia requerida", null);

    private GatewayJwtValidators() {
    }

    public static OAuth2TokenValidator<Jwt> crear(String issuerUri, Duration clockSkew,
                                                  Collection<String> allowedClients, String requiredAudience) {
        List<OAuth2TokenValidator<Jwt>> validadores = new ArrayList<>();
        validadores.add(new JwtTimestampValidator(clockSkew == null ? GatewayHardeningProperties.CLOCK_SKEW_POR_DEFECTO : clockSkew));
        if (issuerUri != null && !issuerUri.isBlank()) {
            validadores.add(new JwtIssuerValidator(issuerUri.trim()));
        }
        Set<String> clientes = allowedClients == null ? Set.of() : Set.copyOf(allowedClients);
        if (!clientes.isEmpty()) {
            validadores.add(jwt -> {
                String azp = jwt.getClaimAsString("azp");
                return (azp != null && clientes.contains(azp))
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(ERROR_CLIENTE);
            });
        }
        if (requiredAudience != null && !requiredAudience.isBlank()) {
            String audiencia = requiredAudience.trim();
            validadores.add(jwt -> {
                List<String> aud = jwt.getAudience();
                return (aud != null && aud.contains(audiencia))
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(ERROR_AUDIENCIA);
            });
        }
        return new DelegatingOAuth2TokenValidator<>(validadores);
    }
}

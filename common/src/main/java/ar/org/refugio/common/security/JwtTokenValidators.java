package ar.org.refugio.common.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Construye el validador compuesto de JWT compartido por los microservicios:
 * 1) exp/nbf con clock skew explicito (por defecto 30 s en lugar de los 60 s implicitos de Spring).
 * 2) emisor exacto (claim "iss").
 * 3) cliente autorizado (claim "azp") dentro de la lista blanca configurada.
 * 4) audiencia obligatoria (claim "aud") cuando se configura JWT_REQUIRED_AUDIENCE.
 */
public final class JwtTokenValidators {

    private static final OAuth2Error ERROR_CLIENTE = new OAuth2Error(
            "invalid_token", "El claim azp del token no corresponde a un cliente autorizado", null);
    private static final OAuth2Error ERROR_AUDIENCIA = new OAuth2Error(
            "invalid_token", "El token no contiene la audiencia requerida", null);

    private JwtTokenValidators() {
    }

    public static OAuth2TokenValidator<Jwt> crear(String issuerUri, Duration clockSkew,
                                                  Collection<String> allowedClients, String requiredAudience) {
        List<OAuth2TokenValidator<Jwt>> validadores = new ArrayList<>();
        validadores.add(new JwtTimestampValidator(clockSkew == null ? JwtValidationProperties.CLOCK_SKEW_POR_DEFECTO : clockSkew));
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

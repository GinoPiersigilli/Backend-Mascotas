package ar.org.refugio.common.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Configura un {@link JwtDecoder} perezoso (no consulta JWKS al arrancar, ADR-05) que aplica
 * validacion estricta de emisor, clock skew explicito, cliente autorizado (azp) y audiencia opcional.
 */
@Configuration
@EnableConfigurationProperties(JwtValidationProperties.class)
@ConditionalOnProperty(prefix = "spring.security.oauth2.resourceserver.jwt", name = {"jwk-set-uri", "issuer-uri"})
public class JwtValidationConfig {

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoder(OAuth2ResourceServerProperties oauthProps, JwtValidationProperties jwtProps) {
        OAuth2ResourceServerProperties.Jwt cfg = oauthProps.getJwt();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(cfg.getJwkSetUri()).build();
        decoder.setJwtValidator(JwtTokenValidators.crear(
                cfg.getIssuerUri(), jwtProps.clockSkew(), jwtProps.allowedClients(), jwtProps.requiredAudience()));
        return decoder;
    }
}

package ar.org.refugio.gateway.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import ar.org.refugio.gateway.config.GatewayHardeningProperties;
import ar.org.refugio.gateway.error.JsonErrorWriter;

import java.util.List;

/**
 * Security Filter del Gateway.
 *
 * Responsabilidad: autenticacion en el borde, cabeceras de seguridad HTTP y politica CORS.
 * Valida firma, emisor y vencimiento del JWT (Resource Server con las claves publicas de Keycloak)
 * y solo deja pasar a /api/** tokens de usuarios (ADOPTANTE o COORDINADOR), salvo el webhook
 * firmado de Mercado Pago (POST /api/donaciones/webhooks/mercadopago), cuya firma HMAC-SHA256
 * verifica ms-donaciones. Un token de servicio (rol SERVICIO) no puede usar la API publica.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, JsonErrorWriter errorWriter,
                                                  GatewayHardeningProperties hardeningProps) {
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRolesConverter());
        jwtConverter.setPrincipalClaimName("sub");

        ServerAuthenticationEntryPoint entryPoint = (exchange, ex) -> errorWriter.escribir(exchange,
                HttpStatus.UNAUTHORIZED, JsonErrorWriter.descripcion(HttpStatus.UNAUTHORIZED),
                "Falta el token o es invalido");
        ServerAccessDeniedHandler accessDenied = (exchange, ex) -> errorWriter.escribir(exchange,
                HttpStatus.FORBIDDEN, JsonErrorWriter.descripcion(HttpStatus.FORBIDDEN),
                "No tiene permisos para realizar esta operacion");

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource(hardeningProps)))
                .headers(headers -> headers
                        .contentTypeOptions(c -> { })
                        .frameOptions(f -> f.mode(XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.NO_REFERRER))
                        .cache(c -> { })
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(ex -> ex
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Webhook publico de Mercado Pago: autenticidad verificada con HMAC x-signature en ms-donaciones
                        .pathMatchers(HttpMethod.POST, "/api/donaciones/webhooks/mercadopago").permitAll()
                        .pathMatchers("/api/**").hasAnyRole("ADOPTANTE", "COORDINADOR")
                        // Todo lo demas (incluida /internal/**, que no tiene ruta) queda cerrado
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(jwtConverter)))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDenied))
                .build();
    }

    private static CorsConfigurationSource corsConfigurationSource(GatewayHardeningProperties props) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!props.corsAllowedOrigins().isEmpty()) {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(props.corsAllowedOrigins());
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-ID", "Idempotency-Key"));
            config.setExposedHeaders(List.of("X-Correlation-ID", "X-Total-Count"));
            config.setMaxAge(3600L);
            source.registerCorsConfiguration("/api/**", config);
        }
        return source;
    }

    @Bean
    @ConditionalOnMissingBean(ReactiveJwtDecoder.class)
    @ConditionalOnProperty(prefix = "spring.security.oauth2.resourceserver.jwt", name = {"jwk-set-uri", "issuer-uri"})
    ReactiveJwtDecoder reactiveJwtDecoder(OAuth2ResourceServerProperties oauthProps,
                                          GatewayHardeningProperties hardeningProps) {
        OAuth2ResourceServerProperties.Jwt cfg = oauthProps.getJwt();
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(cfg.getJwkSetUri()).build();
        decoder.setJwtValidator(GatewayJwtValidators.crear(
                cfg.getIssuerUri(), hardeningProps.jwtClockSkew(),
                hardeningProps.jwtAllowedClients(), hardeningProps.jwtRequiredAudience()));
        return decoder;
    }
}

package ar.org.refugio.adopciones.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import ar.org.refugio.common.security.JsonAccessDeniedHandler;
import ar.org.refugio.common.security.JsonAuthenticationEntryPoint;
import ar.org.refugio.common.security.ResourceServerDefaults;

import static ar.org.refugio.common.security.Roles.ADOPTANTE;
import static ar.org.refugio.common.security.Roles.COORDINADOR;
import static ar.org.refugio.common.security.Roles.SERVICIO;

/**
 * Matriz de permisos de ms-adopciones (CONTRATOS.md, seccion 3.5).
 * Los roles resuelven "que tipo de usuario puede hacer esto"; la pertenencia
 * ("esta solicitud es tuya") la verifica SolicitudService y responde 403.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonAuthenticationEntryPoint entryPoint,
                                            JsonAccessDeniedHandler accessDeniedHandler) throws Exception {
        ResourceServerDefaults.aplicar(http, entryPoint, accessDeniedHandler)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Metricas Prometheus: solo en red interna y con token de servicio (ADR-04)
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").hasRole(SERVICIO)
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Perfil: el propio (ADOPTANTE) y el de cualquier postulante (COORDINADOR)
                        .requestMatchers("/api/adoptantes/me/perfil").hasRole(ADOPTANTE)
                        .requestMatchers(HttpMethod.GET, "/api/adoptantes/*/perfil").hasRole(COORDINADOR)
                        // Acciones del adoptante
                        .requestMatchers(HttpMethod.POST, "/api/solicitudes").hasRole(ADOPTANTE)
                        .requestMatchers(HttpMethod.POST, "/api/solicitudes/*/cancelar", "/api/solicitudes/*/devolucion")
                        .hasRole(ADOPTANTE)
                        // Decisiones y operaciones exclusivas del Coordinador
                        .requestMatchers(HttpMethod.GET, "/api/solicitudes/reportes").hasRole(COORDINADOR)
                        .requestMatchers(HttpMethod.POST, "/api/solicitudes/reconciliacion").hasRole(COORDINADOR)
                        .requestMatchers(HttpMethod.POST, "/api/solicitudes/*/aprobar", "/api/solicitudes/*/rechazar",
                                "/api/solicitudes/*/confirmar-entrega", "/api/solicitudes/*/cancelar-entrega",
                                "/api/solicitudes/*/control-final")
                        .hasRole(COORDINADOR)
                        // Consultas: ambos roles (el filtrado por duenio lo hace el servicio)
                        .requestMatchers(HttpMethod.GET, "/api/solicitudes", "/api/solicitudes/**")
                        .hasAnyRole(ADOPTANTE, COORDINADOR)
                        .anyRequest().denyAll());
        return http.build();
    }
}

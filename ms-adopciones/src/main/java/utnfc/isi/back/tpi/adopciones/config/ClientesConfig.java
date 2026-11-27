package utnfc.isi.back.tpi.adopciones.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import utnfc.isi.back.tpi.adopciones.client.AnimalesClient;
import utnfc.isi.back.tpi.adopciones.client.GeorefClient;
import utnfc.isi.back.tpi.adopciones.client.ServiceTokenProvider;
import utnfc.isi.back.tpi.common.correlation.CorrelationIdInterceptor;
import utnfc.isi.back.tpi.common.exception.ServicioNoDisponibleException;

import java.time.Clock;
import java.time.Duration;

/**
 * Crea los clientes HTTP con RestClient (Spring MVC), timeouts explicitos de conexion y lectura,
 * y circuit breakers de Resilience4j para los clientes con comportamiento degradado definido.
 */
@Configuration
public class ClientesConfig {

    private static final Logger log = LoggerFactory.getLogger(ClientesConfig.class);

    private static SimpleClientHttpRequestFactory conTimeouts(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    private static CircuitBreaker crearCircuito(String nombre, IntegracionesProperties.Circuito cfg) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cfg.failureRateThreshold())
                .slidingWindowSize(cfg.slidingWindowSize())
                .minimumNumberOfCalls(cfg.minimumNumberOfCalls())
                .waitDurationInOpenState(cfg.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(cfg.permittedNumberOfCallsInHalfOpenState())
                .recordExceptions(ServicioNoDisponibleException.class)
                .build();
        CircuitBreaker cb = CircuitBreaker.of(nombre, config);
        cb.getEventPublisher().onStateTransition(ev ->
                log.warn("CircuitBreaker '{}': {}", nombre, ev.getStateTransition()));
        return cb;
    }

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    ServiceTokenProvider serviceTokenProvider(IntegracionesProperties props, Clock clock) {
        IntegracionesProperties.Keycloak kc = props.keycloak();
        RestClient restClient = RestClient.builder()
                .requestFactory(conTimeouts(kc.connectTimeout(), kc.readTimeout()))
                .build();
        return new ServiceTokenProvider(restClient, kc.tokenUri(), kc.clientId(), kc.clientSecret(), clock);
    }

    @Bean
    AnimalesClient animalesClient(IntegracionesProperties props, ServiceTokenProvider tokenProvider) {
        IntegracionesProperties.Animales cfg = props.animales();
        RestClient restClient = RestClient.builder()
                .baseUrl(cfg.baseUrl())
                .requestFactory(conTimeouts(cfg.connectTimeout(), cfg.readTimeout()))
                // reenvia el X-Correlation-ID para seguir la operacion entre servicios
                .requestInterceptor(new CorrelationIdInterceptor())
                .build();
        return new AnimalesClient(restClient, tokenProvider, crearCircuito("animales", cfg.circuitBreaker()));
    }

    @Bean
    GeorefClient georefClient(IntegracionesProperties props) {
        IntegracionesProperties.Georef cfg = props.georef();
        RestClient restClient = RestClient.builder()
                .baseUrl(cfg.baseUrl())
                .requestFactory(conTimeouts(cfg.connectTimeout(), cfg.readTimeout()))
                .build();
        return new GeorefClient(restClient, crearCircuito("georef", cfg.circuitBreaker()));
    }
}

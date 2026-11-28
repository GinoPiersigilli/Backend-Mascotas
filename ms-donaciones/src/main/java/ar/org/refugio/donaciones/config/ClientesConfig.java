package ar.org.refugio.donaciones.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import ar.org.refugio.common.exception.ServicioNoDisponibleException;
import ar.org.refugio.donaciones.client.MercadoPagoClient;

import java.time.Clock;

@Configuration
public class ClientesConfig {

    private static final Logger log = LoggerFactory.getLogger(ClientesConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    MercadoPagoClient mercadoPagoClient(DonacionesProperties props) {
        DonacionesProperties.MercadoPago mp = props.mercadoPago();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(mp.connectTimeout());
        factory.setReadTimeout(mp.readTimeout());
        RestClient restClient = RestClient.builder()
                .baseUrl(mp.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + mp.accessToken())
                .build();
        DonacionesProperties.Circuito cfg = mp.circuitBreaker();
        CircuitBreaker cb = CircuitBreaker.of("mercadoPago", CircuitBreakerConfig.custom()
                .failureRateThreshold(cfg.failureRateThreshold())
                .slidingWindowSize(cfg.slidingWindowSize())
                .minimumNumberOfCalls(cfg.minimumNumberOfCalls())
                .waitDurationInOpenState(cfg.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(cfg.permittedNumberOfCallsInHalfOpenState())
                .recordExceptions(ServicioNoDisponibleException.class)
                .build());
        cb.getEventPublisher().onStateTransition(ev ->
                log.warn("CircuitBreaker 'mercadoPago': {}", ev.getStateTransition()));
        DonacionesProperties.Reintento retryCfg = mp.retry();
        Retry retryConsulta = Retry.of("mercadoPagoConsulta", RetryConfig.custom()
                .maxAttempts(retryCfg.maxAttempts())
                .waitDuration(retryCfg.waitDuration())
                .retryExceptions(ServicioNoDisponibleException.class)
                .build());
        retryConsulta.getEventPublisher().onRetry(ev -> {
            Throwable causa = ev.getLastThrowable();
            String tipoCausa = causa != null ? causa.getClass().getSimpleName() : "desconocida";
            log.warn("Retry 'mercadoPagoConsulta': intento {} tras {}", ev.getNumberOfRetryAttempts(), tipoCausa);
        });
        return new MercadoPagoClient(restClient, mp.usarSandboxInitPoint(), cb, retryConsulta);
    }
}

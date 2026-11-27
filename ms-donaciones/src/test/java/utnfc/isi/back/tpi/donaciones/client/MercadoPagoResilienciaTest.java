package utnfc.isi.back.tpi.donaciones.client;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import utnfc.isi.back.tpi.common.exception.ServicioNoDisponibleException;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica timeouts reales de lectura, apertura del circuit breaker (fail-fast sin pegarle al servidor)
 * y recuperacion a CLOSED para MercadoPagoClient.
 */
class MercadoPagoResilienciaTest {

    @Test
    void timeoutAbreCircuitoRechazaRapidoYSeRecupera() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicBoolean lento = new AtomicBoolean(true);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/payments/search", ex -> {
            hits.incrementAndGet();
            if (lento.get()) {
                try {
                    Thread.sleep(350);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "{\"results\":[{\"id\":777,\"status\":\"approved\"}]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(1));
            factory.setReadTimeout(Duration.ofMillis(120));
            RestClient restClient = RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .requestFactory(factory)
                    .build();
            CircuitBreaker cb = CircuitBreaker.of("mp-test", CircuitBreakerConfig.custom()
                    .failureRateThreshold(50)
                    .slidingWindowSize(2)
                    .minimumNumberOfCalls(2)
                    .waitDurationInOpenState(Duration.ofMillis(180))
                    .permittedNumberOfCallsInHalfOpenState(1)
                    .recordExceptions(ServicioNoDisponibleException.class)
                    .build());
            MercadoPagoClient client = new MercadoPagoClient(restClient, false, cb);

            // 1) Dos timeouts abren el circuito
            assertThatThrownBy(() -> client.buscarPagoAprobado("ref-1"))
                    .isInstanceOf(ServicioNoDisponibleException.class);
            assertThatThrownBy(() -> client.buscarPagoAprobado("ref-1"))
                    .isInstanceOf(ServicioNoDisponibleException.class);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(hits.get()).isEqualTo(2);

            // 2) Circuito abierto: falla rapido sin invocar al servidor HTTP
            assertThatThrownBy(() -> client.buscarPagoAprobado("ref-1"))
                    .isInstanceOf(ServicioNoDisponibleException.class);
            assertThat(hits.get()).isEqualTo(2);

            // 3) Recuperacion tras waitDurationInOpenState -> HALF_OPEN -> CLOSED
            lento.set(false);
            Thread.sleep(230);
            assertThat(client.buscarPagoAprobado("ref-1")).contains("777");
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(hits.get()).isEqualTo(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void consultaDePagoReintentaFallaTransitoriaPeroCrearCobroNuncaSeReintenta() throws Exception {
        AtomicInteger hitsConsulta = new AtomicInteger();
        AtomicInteger hitsCobro = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/payments/search", ex -> {
            if (hitsConsulta.incrementAndGet() == 1) {
                ex.sendResponseHeaders(503, -1);
                ex.close();
                return;
            }
            byte[] body = "{\"results\":[{\"id\":888,\"status\":\"approved\"}]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/checkout/preferences", ex -> {
            hitsCobro.incrementAndGet();
            ex.sendResponseHeaders(503, -1);
            ex.close();
        });
        server.start();
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(1));
            factory.setReadTimeout(Duration.ofSeconds(1));
            RestClient restClient = RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .requestFactory(factory)
                    .build();
            CircuitBreaker cb = CircuitBreaker.of("mp-retry-test", CircuitBreakerConfig.custom()
                    .failureRateThreshold(50)
                    .slidingWindowSize(4)
                    .minimumNumberOfCalls(4)
                    .recordExceptions(ServicioNoDisponibleException.class)
                    .build());
            Retry retry = Retry.of("mp-retry-test", RetryConfig.custom()
                    .maxAttempts(3)
                    .waitDuration(Duration.ofMillis(20))
                    .retryExceptions(ServicioNoDisponibleException.class)
                    .build());
            MercadoPagoClient client = new MercadoPagoClient(restClient, false, cb, retry);

            // GET idempotente: reintenta tras 503 inicial y recupera en el intento 2
            assertThat(client.buscarPagoAprobado("ref-retry")).contains("888");
            assertThat(hitsConsulta.get()).isEqualTo(2);

            // POST no idempotente: falla en el primer intento y NUNCA se reintenta
            assertThatThrownBy(() -> client.crearCobro("ref-no-retry", BigDecimal.valueOf(500), "Donacion"))
                    .isInstanceOf(ServicioNoDisponibleException.class);
            assertThat(hitsCobro.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }
}

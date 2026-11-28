package ar.org.refugio.adopciones.client;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import ar.org.refugio.adopciones.config.PoliticaAdopcion;
import ar.org.refugio.adopciones.model.Perfil;
import ar.org.refugio.adopciones.model.TipoVivienda;
import ar.org.refugio.adopciones.service.EvaluadorSolicitud;
import ar.org.refugio.common.exception.ConflictoException;
import ar.org.refugio.common.exception.RecursoNoEncontradoException;
import ar.org.refugio.common.exception.ServicioNoDisponibleException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifica timeouts reales de lectura, apertura del circuit breaker (fail-fast sin pegarle al servidor),
 * comportamiento degradado y recuperacion a CLOSED para GeorefClient y AnimalesClient.
 */
@Tag("unit")
class ClientesResilienciaTest {

    private static RestClient restClientConTimeout(String baseUrl, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    private static CircuitBreaker circuitoRapido(String nombre) {
        return CircuitBreaker.of(nombre, CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofMillis(100))
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordExceptions(ServicioNoDisponibleException.class)
                .build());
    }

    private static void esperarHalfOpen(CircuitBreaker cb) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (cb.getState() != CircuitBreaker.State.HALF_OPEN && System.nanoTime() < limite) {
            Thread.sleep(15);
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void georefTimeoutAbreCircuitoDegradaADistanciaNoVerificadaYSeRecupera() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicBoolean lento = new AtomicBoolean(true);
        CountDownLatch soltarLentos = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/direcciones", ex -> {
            hits.incrementAndGet();
            if (lento.get()) {
                try {
                    soltarLentos.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = """
                    {"cantidad":1,"direcciones":[{"ubicacion":{"lat":-31.4096,"lon":-64.1966}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            CircuitBreaker cb = circuitoRapido("georef-test");
            GeorefClient client = new GeorefClient(restClientConTimeout(baseUrl, Duration.ofMillis(150)), cb);
            EvaluadorSolicitud evaluador = new EvaluadorSolicitud(client,
                    new PoliticaAdopcion(-31.4334, -64.1918, 100, 7, 30));
            Perfil perfil = new Perfil("sub-1");
            perfil.actualizar("Ana", "351", "Cordoba", "Cordoba", "Av Colon 1000",
                    TipoVivienda.CASA, true, false, false, LocalDateTime.now());

            // 1) Dos timeouts abren el circuito; EvaluadorSolicitud degrada a distanciaNoVerificada
            assertThat(evaluador.evaluarDistancia(perfil).noVerificada()).isTrue();
            assertThat(evaluador.evaluarDistancia(perfil).noVerificada()).isTrue();
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(hits.get()).isEqualTo(2);

            // 2) Circuito abierto: falla rapido sin invocar al servidor HTTP
            assertThat(evaluador.evaluarDistancia(perfil).noVerificada()).isTrue();
            assertThat(hits.get()).isEqualTo(2);

            // 3) Recuperacion tras waitDurationInOpenState -> HALF_OPEN -> CLOSED
            lento.set(false);
            esperarHalfOpen(cb);
            EvaluadorSolicitud.ResultadoDistancia recuperado = evaluador.evaluarDistancia(perfil);
            assertThat(recuperado.noVerificada()).isFalse();
            assertThat(recuperado.distanciaKm()).isNotNull();
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(hits.get()).isEqualTo(3);
        } finally {
            soltarLentos.countDown();
            server.stop(0);
        }
    }

    @Test
    void animalesTimeoutAbreCircuitoResponde503RapidoYSeRecupera() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicBoolean lento = new AtomicBoolean(true);
        CountDownLatch soltarLentos = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/internal/animales/1", ex -> {
            hits.incrementAndGet();
            if (lento.get()) {
                try {
                    soltarLentos.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = """
                    {"id":1,"nombre":"Luna","especie":"PERRO","edadMeses":24,"tamanio":"MEDIANO",
                     "aptoNinios":true,"aptoOtrosAnimales":true,"requiereEspacioExterior":false,"estado":"DISPONIBLE"}
                    """.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ServiceTokenProvider tokens = mock(ServiceTokenProvider.class);
            when(tokens.obtenerToken()).thenReturn("token-servicio");
            CircuitBreaker cb = circuitoRapido("animales-test");
            AnimalesClient client = new AnimalesClient(restClientConTimeout(baseUrl, Duration.ofMillis(150)), tokens, cb);

            // 1) Dos timeouts abren el circuito
            assertThatThrownBy(() -> client.obtener(1L)).isInstanceOf(ServicioNoDisponibleException.class);
            assertThatThrownBy(() -> client.obtener(1L)).isInstanceOf(ServicioNoDisponibleException.class);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(hits.get()).isEqualTo(2);

            // 2) Circuito abierto: falla con ServicioNoDisponibleException sin tocar la red
            assertThatThrownBy(() -> client.obtener(1L)).isInstanceOf(ServicioNoDisponibleException.class);
            assertThat(hits.get()).isEqualTo(2);

            // 3) Recuperacion tras waitDurationInOpenState
            lento.set(false);
            esperarHalfOpen(cb);
            AnimalDto animal = client.obtener(1L);
            assertThat(animal.estado()).isEqualTo(EstadoAnimal.DISPONIBLE);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(hits.get()).isEqualTo(3);
        } finally {
            soltarLentos.countDown();
            server.stop(0);
        }
    }

    @Test
    void animalesErroresDeNegocio404Y409NoAbrenElCircuito() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/animales/99", ex -> {
            byte[] body = "{\"status\":404,\"message\":\"No existe\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(404, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/internal/animales/1/estado", ex -> {
            byte[] body = "{\"status\":409,\"message\":\"Transicion invalida\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(409, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ServiceTokenProvider tokens = mock(ServiceTokenProvider.class);
            when(tokens.obtenerToken()).thenReturn("token-servicio");
            CircuitBreaker cb = circuitoRapido("animales-negocio-test");
            AnimalesClient client = new AnimalesClient(restClientConTimeout(baseUrl, Duration.ofSeconds(1)), tokens, cb);

            assertThatThrownBy(() -> client.obtener(99L)).isInstanceOf(RecursoNoEncontradoException.class);
            assertThatThrownBy(() -> client.obtener(99L)).isInstanceOf(RecursoNoEncontradoException.class);
            assertThatThrownBy(() -> client.cambiarEstado(1L, EstadoAnimal.RESERVADO))
                    .isInstanceOf(ConflictoException.class);
            assertThatThrownBy(() -> client.cambiarEstado(1L, EstadoAnimal.RESERVADO))
                    .isInstanceOf(ConflictoException.class);

            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void georefReintentaFallaTransitoriaYSeRecuperaSinAbrirCircuito() throws Exception {
        AtomicInteger hits = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/direcciones", ex -> {
            if (hits.incrementAndGet() == 1) {
                ex.sendResponseHeaders(503, -1);
                ex.close();
                return;
            }
            byte[] body = """
                    {"cantidad":1,"direcciones":[{"ubicacion":{"lat":-31.4096,"lon":-64.1966}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            CircuitBreaker cb = circuitoRapido("georef-retry-test");
            Retry retry = Retry.of("georef-retry-test", RetryConfig.custom()
                    .maxAttempts(3)
                    .waitDuration(Duration.ofMillis(20))
                    .retryExceptions(ServicioNoDisponibleException.class)
                    .build());
            GeorefClient client = new GeorefClient(restClientConTimeout(baseUrl, Duration.ofSeconds(1)), cb, retry);

            assertThat(client.geolocalizar("Av Colon 1000", "Cordoba", "Cordoba"))
                    .contains(new Coordenadas(-31.4096, -64.1966));
            assertThat(hits.get()).isEqualTo(2);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        } finally {
            server.stop(0);
        }
    }
}

package utnfc.isi.back.tpi.adopciones.client;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import utnfc.isi.back.tpi.common.exception.ServicioNoDisponibleException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Client credentials contra Keycloak: cache sin lock, renovacion al vencer, concurrencia y fallas. */
@Tag("unit")
class ServiceTokenProviderTest {

    private static final String TOKEN_URI = "http://keycloak:8080/realms/adopciones/protocol/openid-connect/token";

    private static final class RelojPrueba extends Clock {
        private volatile Instant ahora;

        RelojPrueba(Instant inicio) {
            this.ahora = inicio;
        }

        void adelantar(Duration d) {
            ahora = ahora.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }

    private MockRestServiceServer server;
    private RelojPrueba reloj;
    private ServiceTokenProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        reloj = new RelojPrueba(Instant.parse("2026-10-27T12:00:00Z"));
        provider = new ServiceTokenProvider(builder.build(), TOKEN_URI, "adopciones-service", "secreto", reloj);
    }

    @Test
    void pideElTokenUnaVezYLoReutilizaMientrasEsteVigente() {
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "grant_type", "client_credentials", "client_id", "adopciones-service")))
                .andRespond(withSuccess("{\"access_token\":\"abc\",\"expires_in\":300}", MediaType.APPLICATION_JSON));

        assertThat(provider.obtenerToken()).isEqualTo("abc");
        reloj.adelantar(Duration.ofSeconds(269));
        assertThat(provider.obtenerToken()).isEqualTo("abc");
        server.verify();
    }

    @Test
    void renuevaAlEntrarEnElMargenDeVencimiento() {
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"tok-1\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"tok-2\",\"expires_in\":300}", MediaType.APPLICATION_JSON));

        assertThat(provider.obtenerToken()).isEqualTo("tok-1");
        // expires_in=300 y margen=30 -> a los 270 s debe renovar
        reloj.adelantar(Duration.ofSeconds(270));
        assertThat(provider.obtenerToken()).isEqualTo("tok-2");
        server.verify();
    }

    @Test
    void trasInvalidarloPideUnoNuevo() {
        server.expect(ExpectedCount.twice(), requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"abc\",\"expires_in\":300}", MediaType.APPLICATION_JSON));

        provider.obtenerToken();
        provider.invalidar();
        provider.obtenerToken();
        server.verify();
    }

    @Test
    void keycloakCaidoSeTraduceAServicioNoDisponible() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withServerError());

        assertThatThrownBy(provider::obtenerToken).isInstanceOf(ServicioNoDisponibleException.class);
    }

    @Test
    void conMultiplesHilosConcurrentesYTokenVencidoHaceUnaSolaLlamadaAKeycloak() throws Exception {
        AtomicInteger llamadas = new AtomicInteger();
        RestClient restClient = RestClient.builder()
                .requestInterceptor((req, body, exec) -> {
                    int numero = llamadas.incrementAndGet();
                    try {
                        Thread.sleep(60);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    String json = "{\"access_token\":\"tok-" + numero + "\",\"expires_in\":60}";
                    MockClientHttpResponse resp = new MockClientHttpResponse(
                            json.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                    resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return resp;
                })
                .build();
        RelojPrueba relojConcurrente = new RelojPrueba(Instant.parse("2026-10-27T12:00:00Z"));
        ServiceTokenProvider concurrentProvider = new ServiceTokenProvider(
                restClient, TOKEN_URI, "adopciones-service", "secreto", relojConcurrente);

        // Token inicial
        assertThat(concurrentProvider.obtenerToken()).isEqualTo("tok-1");
        assertThat(llamadas.get()).isEqualTo(1);

        // Vence el token y 16 hilos virtuales piden el token simultaneamente
        relojConcurrente.adelantar(Duration.ofSeconds(61));
        int hilos = 16;
        CountDownLatch listos = new CountDownLatch(hilos);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<String>> futuros = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < hilos; i++) {
                futuros.add(pool.submit(() -> {
                    listos.countDown();
                    largada.await();
                    return concurrentProvider.obtenerToken();
                }));
            }
            listos.await();
            largada.countDown();

            for (Future<String> futuro : futuros) {
                assertThat(futuro.get()).isEqualTo("tok-2");
            }
        }

        // 1 inicial + exactamente 1 renovacion compartida por los 16 hilos
        assertThat(llamadas.get()).isEqualTo(2);
    }
}

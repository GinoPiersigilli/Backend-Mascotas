package utnfc.isi.back.tpi.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import utnfc.isi.back.tpi.gateway.filter.CorrelationIdGlobalFilter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Verifica que el Gateway propague X-Correlation-ID hacia el microservicio enrutado,
 * lo devuelva en la respuesta y lo incluya en el MDC de los logs con el formato [gateway] [id].
 */
@SpringBootTest(classes = GatewayApplication.class)
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
@Tag("integration")
class GatewayCorrelationTest {

    private static HttpServer backendSimulado;
    private static final AtomicReference<String> ultimoCorrelationRecibido = new AtomicReference<>();

    @BeforeAll
    static void iniciarBackend() throws IOException {
        backendSimulado = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendSimulado.createContext("/api/animales", exchange -> {
            ultimoCorrelationRecibido.set(exchange.getRequestHeaders().getFirst(CorrelationIdGlobalFilter.HEADER));
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        backendSimulado.start();
    }

    @AfterAll
    static void detenerBackend() {
        if (backendSimulado != null) {
            backendSimulado.stop(0);
        }
    }

    @DynamicPropertySource
    static void registrarRutas(DynamicPropertyRegistry registry) {
        registry.add("ANIMALES_URL", () -> "http://127.0.0.1:" + backendSimulado.getAddress().getPort());
        registry.add("ADOPCIONES_URL", () -> "http://127.0.0.1:1");
        registry.add("DONACIONES_URL", () -> "http://127.0.0.1:1");
    }

    @Autowired
    private WebTestClient client;

    private WebTestClient adoptante() {
        return client.mutateWith(mockJwt().jwt(j -> j.subject("u-adoptante"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADOPTANTE")));
    }

    @Test
    void propagaHeaderARutaYLoMuestraEnLogsConFormatoUniforme(CapturedOutput output) {
        ultimoCorrelationRecibido.set(null);

        adoptante().get().uri("/api/animales")
                .header(CorrelationIdGlobalFilter.HEADER, "traza-e2e-42")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(CorrelationIdGlobalFilter.HEADER, "traza-e2e-42");

        assertThat(ultimoCorrelationRecibido.get()).isEqualTo("traza-e2e-42");
        assertThat(output.getOut()).contains("[gateway] [traza-e2e-42]");
    }

    @Test
    void siNoVieneHeaderGeneraUnoLoEnviaAlServicioYLoRegistraEnLogs(CapturedOutput output) {
        ultimoCorrelationRecibido.set(null);

        String devuelto = adoptante().get().uri("/api/animales")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(CorrelationIdGlobalFilter.HEADER)
                .returnResult(String.class)
                .getResponseHeaders()
                .getFirst(CorrelationIdGlobalFilter.HEADER);

        assertThat(devuelto).isNotBlank();
        assertThat(ultimoCorrelationRecibido.get()).isEqualTo(devuelto);
        assertThat(output.getOut()).contains("[gateway] [" + devuelto + "]");
    }

    @Test
    void errorDeServicioCaidoRegistraCorrelationIdEnMdc(CapturedOutput output) {
        adoptante().get().uri("/api/solicitudes")
                .header(CorrelationIdGlobalFilter.HEADER, "traza-err-99")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(CorrelationIdGlobalFilter.HEADER, "traza-err-99");

        assertThat(output.getOut()).contains("[gateway] [traza-err-99]");
    }
}

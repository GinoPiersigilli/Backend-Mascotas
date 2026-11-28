package ar.org.refugio.common.correlation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class CorrelationIdInterceptorTest {

    private final CorrelationIdInterceptor interceptor = new CorrelationIdInterceptor();

    @AfterEach
    void limpiarMdc() {
        MDC.clear();
    }

    @Test
    void propagaCorrelationIdDelMdcCuandoNoEstaEnElRequest() throws Exception {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-123");
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://localhost/test"));
        ClientHttpRequestExecution execution = (req, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK);

        try (ClientHttpResponse response = interceptor.intercept(request, new byte[0], execution)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(request.getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo("corr-123");
    }

    @Test
    void noSobrescribeHeaderSiYaVienePresente() throws Exception {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-mdc");
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://localhost/test"));
        request.getHeaders().add(CorrelationIdFilter.HEADER, "corr-explicito");
        ClientHttpRequestExecution execution = (req, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK);

        try (ClientHttpResponse response = interceptor.intercept(request, new byte[0], execution)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(request.getHeaders().get(CorrelationIdFilter.HEADER)).containsExactly("corr-explicito");
    }

    @Test
    void noAgregaHeaderCuandoMdcEstaVacio() throws Exception {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://localhost/test"));
        ClientHttpRequestExecution execution = (req, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK);

        try (ClientHttpResponse response = interceptor.intercept(request, new byte[0], execution)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(request.getHeaders().containsKey(CorrelationIdFilter.HEADER)).isFalse();
    }
}

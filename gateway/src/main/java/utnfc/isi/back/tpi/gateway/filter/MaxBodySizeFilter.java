package utnfc.isi.back.tpi.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import utnfc.isi.back.tpi.gateway.config.GatewayHardeningProperties;
import utnfc.isi.back.tpi.gateway.error.JsonErrorWriter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Limita el tamanio maximo del cuerpo HTTP en el API Gateway (F6):
 * - Rechaza de inmediato con 413 JSON si Content-Length supera el limite configurado.
 * - Tambien cuenta bytes en streaming para peticiones chunked sin Content-Length.
 */
@Component
public class MaxBodySizeFilter implements GlobalFilter, Ordered {

    private final GatewayHardeningProperties props;
    private final JsonErrorWriter errorWriter;

    public MaxBodySizeFilter(GatewayHardeningProperties props, JsonErrorWriter errorWriter) {
        this.props = props;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long limite = props.maxBodyBytes();
        long contentLength = exchange.getRequest().getHeaders().getContentLength();
        if (contentLength > limite) {
            return errorWriter.escribir(exchange, HttpStatus.PAYLOAD_TOO_LARGE,
                    JsonErrorWriter.descripcion(HttpStatus.PAYLOAD_TOO_LARGE),
                    "El cuerpo de la solicitud supera el limite maximo de " + limite + " bytes");
        }

        AtomicLong acumulado = new AtomicLong(0);
        ServerHttpRequest requestDecorado = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return super.getBody().handle((buffer, sink) -> {
                    if (acumulado.addAndGet(buffer.readableByteCount()) > limite) {
                        DataBufferUtils.release(buffer);
                        sink.error(new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                                "El cuerpo de la solicitud supera el limite maximo de " + limite + " bytes"));
                    } else {
                        sink.next(buffer);
                    }
                });
            }
        };

        return chain.filter(exchange.mutate().request(requestDecorado).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}

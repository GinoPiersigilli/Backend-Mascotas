package utnfc.isi.back.tpi.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Genera el X-Correlation-ID si no viene, lo agrega a la peticion que se reenvia al servicio y a
 * toda respuesta (incluidos 401, 403, 404, 503 y 504 al ejecutarse como WebFilter antes de seguridad).
 * Con el mismo identificador se puede seguir una operacion en los logs del Gateway y los servicios.
 */
@Component
public class CorrelationIdGlobalFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-Correlation-ID";
    public static final String ATTR = CorrelationIdGlobalFilter.class.getName() + ".CORRELATION_ID";
    private static final Logger log = LoggerFactory.getLogger(CorrelationIdGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String recibido = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = (recibido == null || recibido.isBlank() || recibido.length() > 100)
                ? UUID.randomUUID().toString() : recibido;
        exchange.getAttributes().put(ATTR, correlationId);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(HEADER, correlationId))
                .build();
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, correlationId);
            return Mono.empty();
        });
        log.info("[{}] {} {}", correlationId, request.getMethod(), request.getPath());
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

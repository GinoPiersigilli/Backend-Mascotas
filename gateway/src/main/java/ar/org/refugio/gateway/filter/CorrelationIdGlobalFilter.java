package ar.org.refugio.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Genera el X-Correlation-ID si no viene, lo agrega a la peticion que se reenvia al servicio,
 * lo publica en el Context de Reactor (propagado al MDC via Micrometer context-propagation) y lo
 * devuelve en toda respuesta (incluidos 401, 403, 404, 503 y 504 al ejecutarse antes de seguridad).
 */
@Component
public class CorrelationIdGlobalFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";
    public static final String ATTR = CorrelationIdGlobalFilter.class.getName() + ".CORRELATION_ID";
    private static final Pattern PATRON_SEGURO = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");
    private static final Logger log = LoggerFactory.getLogger(CorrelationIdGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String recibido = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = (recibido == null || !PATRON_SEGURO.matcher(recibido).matches())
                ? UUID.randomUUID().toString() : recibido;
        exchange.getAttributes().put(ATTR, correlationId);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(HEADER, correlationId))
                .build();
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, correlationId);
            return Mono.empty();
        });
        ServerWebExchange mutated = exchange.mutate().request(request).build();
        return Mono.defer(() -> {
                    try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_KEY, correlationId)) {
                        log.info("{} {}", request.getMethod(), request.getPath());
                    }
                    return chain.filter(mutated);
                })
                .contextWrite(Context.of(MDC_KEY, correlationId));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

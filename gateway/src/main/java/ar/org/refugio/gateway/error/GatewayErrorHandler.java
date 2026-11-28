package ar.org.refugio.gateway.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import ar.org.refugio.gateway.filter.CorrelationIdGlobalFilter;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeoutException;

/**
 * Traduce los errores del Gateway al formato unico:
 * - el servicio tardo mas que el response-timeout -> 504;
 * - servicio interno caido o inaccesible -> 503 (CONTRATOS.md: "otro servicio no responde");
 * - ruta inexistente y otros ResponseStatusException -> su codigo.
 * Se ejecuta antes que el manejador por defecto de Spring Boot (orden -1).
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final JsonErrorWriter errorWriter;

    public GatewayErrorHandler(JsonErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        String correlationId = exchange.getAttributeOrDefault(CorrelationIdGlobalFilter.ATTR, "-");
        try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationIdGlobalFilter.MDC_KEY, correlationId)) {
            if (esCausadoPor(ex, DataBufferLimitException.class)) {
                log.warn("Cuerpo demasiado grande para {}: {}", exchange.getRequest().getPath(), ex.getMessage());
                return errorWriter.escribir(exchange, HttpStatus.PAYLOAD_TOO_LARGE,
                        JsonErrorWriter.descripcion(HttpStatus.PAYLOAD_TOO_LARGE),
                        "El cuerpo de la solicitud supera el limite maximo permitido");
            }
            if (esCausadoPor(ex, TimeoutException.class)) {
                log.warn("Tiempo de espera agotado para {}: {}", exchange.getRequest().getPath(), ex.getMessage());
                return errorWriter.escribir(exchange, HttpStatus.GATEWAY_TIMEOUT,
                        JsonErrorWriter.descripcion(HttpStatus.GATEWAY_TIMEOUT),
                        "El servicio que atiende esta ruta no respondio a tiempo");
            }
            if (esCausadoPor(ex, ConnectException.class) || esCausadoPor(ex, UnknownHostException.class)
                    || esCausadoPor(ex, ClosedChannelException.class) || esCausadoPor(ex, IOException.class)) {
                log.warn("Servicio interno no disponible para {}: {}", exchange.getRequest().getPath(), ex.getMessage());
                return errorWriter.escribir(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                        JsonErrorWriter.descripcion(HttpStatus.SERVICE_UNAVAILABLE),
                        "El servicio que atiende esta ruta no esta disponible");
            }
            if (ex instanceof ResponseStatusException rse) {
                HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
                if (status == null) {
                    status = HttpStatus.INTERNAL_SERVER_ERROR;
                }
                if (status == HttpStatus.GATEWAY_TIMEOUT) {
                    return errorWriter.escribir(exchange, status, JsonErrorWriter.descripcion(status),
                            "El servicio que atiende esta ruta no respondio a tiempo");
                }
                String reason = rse.getReason();
                String mensaje = status == HttpStatus.NOT_FOUND
                        ? "No existe el recurso " + exchange.getRequest().getPath().value()
                        : (reason != null && !reason.isBlank() ? reason : status.getReasonPhrase());
                return errorWriter.escribir(exchange, status, JsonErrorWriter.descripcion(status), mensaje);
            }
            log.error("Error inesperado en el Gateway para {}", exchange.getRequest().getPath(), ex);
            return errorWriter.escribir(exchange, HttpStatus.INTERNAL_SERVER_ERROR,
                    JsonErrorWriter.descripcion(HttpStatus.INTERNAL_SERVER_ERROR),
                    "Error interno del Gateway");
        }
    }

    private static boolean esCausadoPor(Throwable ex, Class<? extends Throwable> tipo) {
        for (Throwable t = ex; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            if (tipo.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}

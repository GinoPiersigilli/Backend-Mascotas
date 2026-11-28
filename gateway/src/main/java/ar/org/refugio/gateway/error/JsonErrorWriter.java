package ar.org.refugio.gateway.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Escribe errores con el mismo formato JSON que usan los servicios (CONTRATOS.md, seccion 4),
 * para que el consumidor reciba siempre la misma estructura aunque el error ocurra en el Gateway.
 */
@Component
public class JsonErrorWriter {

    private final ObjectMapper objectMapper;

    public JsonErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private record ErrorGateway(
            LocalDateTime timestamp,
            int status,
            String error,
            String message,
            String path,
            List<Object> errores) {
    }

    public Mono<Void> escribir(ServerWebExchange exchange, HttpStatus status, String error, String mensaje) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        ErrorGateway body = new ErrorGateway(LocalDateTime.now(), status.value(), error, mensaje,
                exchange.getRequest().getPath().value(), List.of());
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            String fallback = "{\"status\":" + status.value() + ",\"error\":\"" + status.getReasonPhrase()
                    + "\",\"message\":\"Error al serializar la respuesta\",\"errores\":[]}";
            bytes = fallback.getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /** Texto en castellano del campo "error", alineado con common/ErrorResponse. */
    public static String descripcion(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "Solicitud invalida";
            case UNAUTHORIZED -> "No autenticado";
            case FORBIDDEN -> "Prohibido";
            case NOT_FOUND -> "No encontrado";
            case METHOD_NOT_ALLOWED -> "Metodo no permitido";
            case CONFLICT -> "Conflicto";
            case PAYLOAD_TOO_LARGE -> "Cuerpo demasiado grande";
            case UNSUPPORTED_MEDIA_TYPE -> "Tipo de contenido no soportado";
            case SERVICE_UNAVAILABLE -> "Servicio no disponible";
            case GATEWAY_TIMEOUT -> "Tiempo de espera agotado";
            case INTERNAL_SERVER_ERROR -> "Error interno";
            default -> status.getReasonPhrase();
        };
    }
}

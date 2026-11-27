package utnfc.isi.back.tpi.common.error;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import utnfc.isi.back.tpi.common.exception.AccesoDenegadoException;
import utnfc.isi.back.tpi.common.exception.ConflictoException;
import utnfc.isi.back.tpi.common.exception.RecursoNoEncontradoException;
import utnfc.isi.back.tpi.common.exception.ServicioNoDisponibleException;
import utnfc.isi.back.tpi.common.exception.SolicitudInvalidaException;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manejador global de excepciones, compartido por los tres servicios.
 * Traduce cada excepcion al formato unico {@link ErrorResponse}.
 *
 * Los 401/403 que ocurren en la cadena de filtros de Spring Security (antes de llegar al
 * controlador) los resuelven {@code JsonAuthenticationEntryPoint} y {@code JsonAccessDeniedHandler};
 * los de aca cubren los que se lanzan dentro del controlador o del servicio.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<ErrorResponse> handleNoEncontrado(RecursoNoEncontradoException ex, HttpServletRequest req) {
        log.warn("Recurso no encontrado en {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req, List.of());
    }

    @ExceptionHandler(ConflictoException.class)
    public ResponseEntity<ErrorResponse> handleConflicto(ConflictoException ex, HttpServletRequest req) {
        log.warn("Conflicto en {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), req, List.of());
    }

    @ExceptionHandler(ServicioNoDisponibleException.class)
    public ResponseEntity<ErrorResponse> handleNoDisponible(ServicioNoDisponibleException ex, HttpServletRequest req) {
        log.warn("Servicio no disponible en {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), req, List.of());
    }

    @ExceptionHandler(SolicitudInvalidaException.class)
    public ResponseEntity<ErrorResponse> handleSolicitudInvalida(SolicitudInvalidaException ex, HttpServletRequest req) {
        log.warn("Solicitud invalida en {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req, List.of());
    }

    @ExceptionHandler(AccesoDenegadoException.class)
    public ResponseEntity<ErrorResponse> handleAccesoDenegado(AccesoDenegadoException ex, HttpServletRequest req) {
        log.warn("Acceso denegado en {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), req, List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        log.warn("Rol insuficiente en {}", req.getRequestURI());
        return build(HttpStatus.FORBIDDEN, "No tiene permisos para realizar esta operacion", req, List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, "Falta el token o es invalido", req, List.of());
    }

    /** Dos operaciones concurrentes modificaron el mismo registro (bloqueo optimista con @Version). */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleConcurrencia(OptimisticLockingFailureException ex, HttpServletRequest req) {
        log.warn("Modificacion concurrente en {}", req.getRequestURI());
        return build(HttpStatus.CONFLICT, "El recurso fue modificado por otra operacion; vuelva a consultarlo", req, List.of());
    }

    /** Ultima linea de defensa: una restriccion de la base impidio la operacion. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegridad(DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("Violacion de integridad en {}", req.getRequestURI());
        return build(HttpStatus.CONFLICT, "La operacion viola una restriccion de integridad", req, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidacion(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ErrorResponse.CampoError> campos = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.CampoError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        log.warn("Validacion fallida en {}: {} campo(s) invalido(s)", req.getRequestURI(), campos.size());
        return build(HttpStatus.BAD_REQUEST, "Hay datos invalidos en la solicitud", req, campos);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidacionDeParametros(HandlerMethodValidationException ex,
                                                                      HttpServletRequest req) {
        List<ErrorResponse.CampoError> campos = ex.getParameterValidationResults().stream()
                .flatMap(res -> {
                    String nombre = res.getMethodParameter().getParameterName();
                    String campo = (nombre != null && !nombre.isBlank()) ? nombre : "parametro";
                    return res.getResolvableErrors().stream()
                            .map(err -> new ErrorResponse.CampoError(campo, err.getDefaultMessage()));
                })
                .toList();
        log.warn("Validacion de parametros fallida en {}: {} error(es)", req.getRequestURI(), campos.size());
        return build(HttpStatus.BAD_REQUEST, "Hay datos invalidos en la solicitud", req, campos);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest req) {
        List<ErrorResponse.CampoError> campos = ex.getConstraintViolations().stream()
                .map(cv -> {
                    String ruta = cv.getPropertyPath() == null ? "" : cv.getPropertyPath().toString();
                    int punto = ruta.lastIndexOf('.');
                    String campo = punto >= 0 ? ruta.substring(punto + 1) : ruta;
                    return new ErrorResponse.CampoError(campo, cv.getMessage());
                })
                .toList();
        log.warn("Restriccion de validacion incumplida en {}: {} error(es)", req.getRequestURI(), campos.size());
        return build(HttpStatus.BAD_REQUEST, "Hay datos invalidos en la solicitud", req, campos);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleCuerpoIlegible(HttpMessageNotReadableException ex, HttpServletRequest req) {
        List<ErrorResponse.CampoError> campos = extraerErroresDeLectura(ex);
        if (!campos.isEmpty()) {
            log.warn("Valor invalido al leer JSON en {}: {}", req.getRequestURI(), campos.get(0).campo());
            return build(HttpStatus.BAD_REQUEST, "Hay datos invalidos en la solicitud", req, campos);
        }
        log.warn("Cuerpo ilegible en {}", req.getRequestURI());
        return build(HttpStatus.BAD_REQUEST, "El cuerpo de la solicitud no es un JSON valido o tiene valores no permitidos", req, List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTipoIncorrecto(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        Class<?> requerido = ex.getRequiredType();
        String mensaje = (requerido != null && requerido.isEnum())
                ? "valor invalido; valores permitidos: " + valoresPermitidos(requerido)
                : "valor invalido";
        return build(HttpStatus.BAD_REQUEST, "El parametro '" + ex.getName() + "' tiene un valor invalido", req,
                List.of(new ErrorResponse.CampoError(ex.getName(), mensaje)));
    }

    private static List<ErrorResponse.CampoError> extraerErroresDeLectura(Throwable ex) {
        for (Throwable t = ex; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            if (t instanceof MismatchedInputException mie) {
                String campo = construirRuta(mie.getPath());
                if (!campo.isEmpty()) {
                    String mensaje = (t instanceof InvalidFormatException ife
                            && ife.getTargetType() != null && ife.getTargetType().isEnum())
                            ? "valor invalido; valores permitidos: " + valoresPermitidos(ife.getTargetType())
                            : "tiene un tipo o formato invalido";
                    return List.of(new ErrorResponse.CampoError(campo, mensaje));
                }
            }
        }
        return List.of();
    }

    private static String construirRuta(List<JsonMappingException.Reference> path) {
        StringBuilder sb = new StringBuilder();
        for (JsonMappingException.Reference ref : path) {
            if (ref.getFieldName() != null) {
                if (!sb.isEmpty()) {
                    sb.append('.');
                }
                sb.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.toString();
    }

    private static String valoresPermitidos(Class<?> enumType) {
        return Arrays.stream(enumType.getEnumConstants())
                .map(Object::toString)
                .collect(Collectors.joining(", "));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleParametroFaltante(MissingServletRequestParameterException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Falta el parametro '" + ex.getParameterName() + "'", req, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMetodoNoPermitido(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "Metodo HTTP no permitido para este recurso", req, List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNoSoportado(HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de contenido no soportado", req, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleRutaInexistente(NoResourceFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "No existe el recurso " + req.getRequestURI(), req, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleInesperado(Exception ex, HttpServletRequest req) {
        // Se registra con la traza completa, pero al cliente no se le exponen detalles internos.
        log.error("Error inesperado en {}", req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor", req, List.of());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest req,
                                                List<ErrorResponse.CampoError> errores) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status, message, req.getRequestURI(), errores));
    }
}

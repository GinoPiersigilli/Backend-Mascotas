package utnfc.isi.back.tpi.adopciones.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import utnfc.isi.back.tpi.common.error.ErrorResponse;
import utnfc.isi.back.tpi.common.exception.ConflictoException;
import utnfc.isi.back.tpi.common.exception.RecursoNoEncontradoException;
import utnfc.isi.back.tpi.common.exception.ServicioNoDisponibleException;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Cliente de la API interna de ms-animales (CONTRATOS.md, seccion 6.2).
 *
 * Traduce las respuestas de Animales a las excepciones propias de Adopciones, para que este
 * servicio nunca devuelva la excepcion original de otro (CONTRATOS.md, seccion 4):
 * 404 -> RecursoNoEncontrado, 409 -> Conflicto (con el mensaje de Animales),
 * error de red, timeout, 5xx, 401/403 o respuesta ilegible -> ServicioNoDisponible (503).
 */
public class AnimalesClient {

    private static final Logger log = LoggerFactory.getLogger(AnimalesClient.class);

    private final RestClient restClient;
    private final ServiceTokenProvider tokenProvider;
    private final CircuitBreaker circuitBreaker;

    public AnimalesClient(RestClient restClient, ServiceTokenProvider tokenProvider) {
        this(restClient, tokenProvider, CircuitBreaker.of("animales", CircuitBreakerConfig.custom()
                .recordExceptions(ServicioNoDisponibleException.class)
                .build()));
    }

    public AnimalesClient(RestClient restClient, ServiceTokenProvider tokenProvider, CircuitBreaker circuitBreaker) {
        this.restClient = restClient;
        this.tokenProvider = tokenProvider;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * Precalienta el token de servicio antes de abrir una transaccion de base de datos,
     * para evitar un POST bloqueante a Keycloak mientras se retiene la conexion SQL.
     */
    public void asegurarToken() {
        tokenProvider.obtenerToken();
    }

    public AnimalDto obtener(Long animalId) {
        return ejecutarConCircuito(() -> consultarAnimal(animalId));
    }

    private AnimalDto consultarAnimal(Long animalId) {
        try {
            AnimalDto animal = restClient.get()
                    .uri("/internal/animales/{id}", animalId)
                    .headers(h -> h.setBearerAuth(tokenProvider.obtenerToken()))
                    .retrieve()
                    .body(AnimalDto.class);
            if (animal == null || animal.estado() == null) {
                throw new ServicioNoDisponibleException("El servicio de Animales devolvio una respuesta inesperada");
            }
            return animal;
        } catch (RestClientResponseException e) {
            throw traducir(e, animalId);
        } catch (RestClientException e) {
            log.warn("Animales no responde al consultar el animal {}: {}", animalId, e.getMessage());
            throw new ServicioNoDisponibleException("El servicio de Animales no responde", e);
        }
    }

    /** Pide a Animales una transicion de estado; Animales decide si es valida. */
    public AnimalDto cambiarEstado(Long animalId, EstadoAnimal nuevoEstado) {
        return ejecutarConCircuito(() -> solicitarCambioDeEstado(animalId, nuevoEstado));
    }

    private AnimalDto solicitarCambioDeEstado(Long animalId, EstadoAnimal nuevoEstado) {
        try {
            AnimalDto animal = restClient.put()
                    .uri("/internal/animales/{id}/estado", animalId)
                    .headers(h -> h.setBearerAuth(tokenProvider.obtenerToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("nuevoEstado", nuevoEstado.name()))
                    .retrieve()
                    .body(AnimalDto.class);
            log.info("Animales confirmo el animal {} -> {}", animalId, nuevoEstado);
            return animal;
        } catch (RestClientResponseException e) {
            throw traducir(e, animalId);
        } catch (RestClientException e) {
            log.warn("Animales no responde al cambiar el estado del animal {}: {}", animalId, e.getMessage());
            throw new ServicioNoDisponibleException("El servicio de Animales no responde", e);
        }
    }

    private <T> T ejecutarConCircuito(Supplier<T> llamada) {
        try {
            return circuitBreaker.executeSupplier(llamada);
        } catch (CallNotPermittedException e) {
            log.warn("CircuitBreaker '{}' abierto: llamada a ms-animales rechazada rapido", circuitBreaker.getName());
            throw new ServicioNoDisponibleException("El servicio de Animales no esta disponible temporalmente", e);
        }
    }

    private RuntimeException traducir(RestClientResponseException e, Long animalId) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == HttpStatus.NOT_FOUND) {
            return new RecursoNoEncontradoException("No existe el animal " + animalId);
        }
        if (status == HttpStatus.CONFLICT) {
            return new ConflictoException(mensajeDe(e, "El animal " + animalId + " no admite ese cambio de estado"));
        }
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            // Problema de configuracion del cliente de servicio: no es culpa del usuario
            tokenProvider.invalidar();
            log.error("Animales rechazo el token de servicio ({}). Revisar el cliente adopciones-service en Keycloak", status);
        } else {
            log.warn("Animales respondio {} para el animal {}", e.getStatusCode(), animalId);
        }
        return new ServicioNoDisponibleException("El servicio de Animales no pudo procesar la operacion", e);
    }

    private static String mensajeDe(RestClientResponseException e, String porDefecto) {
        try {
            ErrorResponse error = e.getResponseBodyAs(ErrorResponse.class);
            return error != null && error.message() != null ? error.message() : porDefecto;
        } catch (RuntimeException ignorada) {
            return porDefecto;
        }
    }
}

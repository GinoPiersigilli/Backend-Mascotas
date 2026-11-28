package ar.org.refugio.adopciones.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ar.org.refugio.common.exception.ServicioNoDisponibleException;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Obtiene de Keycloak el token del cliente confidencial "adopciones-service"
 * (OAuth2 client credentials) y lo reutiliza mientras no este por vencer.
 *
 * Se usa para llamar a la API interna de Animales tanto en operaciones iniciadas por un
 * usuario como en la tarea programada, donde no hay ningun usuario. El token del usuario
 * NO se reenvia a Animales: la API interna exige el rol SERVICIO, que ningun humano tiene.
 *
 * Concurrencia: la lectura del token vigente es libre de bloqueos mediante un campo
 * {@code volatile}. Solo la renovacion adquiere un {@link ReentrantLock} con doble
 * verificacion, evitando fijar (pinning) virtual threads de Java 21 durante I/O HTTP.
 */
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);
    /** Margen para renovar el token antes de que venza durante una llamada. */
    private static final long MARGEN_SEGUNDOS = 30;

    private record TokenCacheado(String valor, Instant vencimiento) {
        boolean esValido(Instant ahora) {
            return ahora.isBefore(vencimiento.minusSeconds(MARGEN_SEGUNDOS));
        }
    }

    private final RestClient restClient;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile TokenCacheado cache;

    public ServiceTokenProvider(RestClient restClient, String tokenUri, String clientId, String clientSecret, Clock clock) {
        this.restClient = restClient;
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
    }

    public String obtenerToken() {
        TokenCacheado actual = cache;
        if (actual != null && actual.esValido(clock.instant())) {
            return actual.valor();
        }
        lock.lock();
        try {
            actual = cache;
            if (actual != null && actual.esValido(clock.instant())) {
                return actual.valor();
            }
            TokenCacheado renovado = renovar();
            cache = renovado;
            return renovado.valor();
        } finally {
            lock.unlock();
        }
    }

    /** Descarta el token cacheado (por ejemplo, si Animales lo rechazo con 401). */
    public void invalidar() {
        cache = null;
    }

    private TokenCacheado renovar() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        try {
            Map<?, ?> respuesta = restClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            if (respuesta == null || !(respuesta.get("access_token") instanceof String accessToken)) {
                throw new ServicioNoDisponibleException("Keycloak devolvio una respuesta sin access_token");
            }
            long expiraEn = respuesta.get("expires_in") instanceof Number n ? n.longValue() : 60;
            Instant vencimiento = clock.instant().plusSeconds(expiraEn);
            log.debug("Token de servicio renovado; vence en {} s", expiraEn);
            return new TokenCacheado(accessToken, vencimiento);
        } catch (RestClientException e) {
            // Nunca se registra el secreto ni el token
            log.warn("No se pudo obtener el token de servicio: {}", e.getClass().getSimpleName());
            throw new ServicioNoDisponibleException("No se pudo obtener el token de servicio de Keycloak", e);
        }
    }
}

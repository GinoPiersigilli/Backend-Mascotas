package utnfc.isi.back.tpi.adopciones.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import utnfc.isi.back.tpi.adopciones.config.MetricasProperties;
import utnfc.isi.back.tpi.adopciones.model.EstadoSolicitud;
import utnfc.isi.back.tpi.adopciones.repository.SolicitudRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * Expone en Micrometer/Prometheus la cantidad actual de solicitudes agrupadas por estado
 * ({@code adopciones.solicitudes.actuales{estado="..."}}).
 *
 * Usa una sola consulta GROUP BY compartida por todos los estados dentro de una ventana corta
 * ({@code adopciones.metricas.cache-ttl}, 5 s por defecto) para no multiplicar queries por scrape.
 */
@Component
public class MetricasSolicitudes {

    public static final String METRICA_SOLICITUDES_POR_ESTADO = "adopciones.solicitudes.actuales";
    private static final Logger log = LoggerFactory.getLogger(MetricasSolicitudes.class);

    private record Snapshot(Instant validoHasta, Map<EstadoSolicitud, Long> conteos) {
    }

    private final SolicitudRepository solicitudRepository;
    private final Clock clock;
    private final Duration cacheTtl;
    private volatile Snapshot snapshot;

    public MetricasSolicitudes(SolicitudRepository solicitudRepository, MeterRegistry meterRegistry,
                               Clock clock, MetricasProperties props) {
        this.solicitudRepository = solicitudRepository;
        this.clock = clock;
        this.cacheTtl = props.cacheTtl();

        for (EstadoSolicitud estado : EstadoSolicitud.values()) {
            Gauge.builder(METRICA_SOLICITUDES_POR_ESTADO, this, m -> m.contar(estado))
                    .description("Cantidad actual de solicitudes de adopcion por estado")
                    .tag("estado", estado.name())
                    .register(meterRegistry);
        }
    }

    double contar(EstadoSolicitud estado) {
        Map<EstadoSolicitud, Long> conteos = obtenerConteos();
        if (conteos == null) {
            return Double.NaN;
        }
        return conteos.getOrDefault(estado, 0L);
    }

    private Map<EstadoSolicitud, Long> obtenerConteos() {
        Instant ahora = clock.instant();
        Snapshot actual = snapshot;
        if (actual != null && !cacheTtl.isZero() && !ahora.isAfter(actual.validoHasta())) {
            return actual.conteos();
        }
        synchronized (this) {
            actual = snapshot;
            ahora = clock.instant();
            if (actual != null && !cacheTtl.isZero() && !ahora.isAfter(actual.validoHasta())) {
                return actual.conteos();
            }
            try {
                EnumMap<EstadoSolicitud, Long> mapa = new EnumMap<>(EstadoSolicitud.class);
                for (EstadoSolicitud estado : EstadoSolicitud.values()) {
                    mapa.put(estado, 0L);
                }
                for (Object[] fila : solicitudRepository.contarPorEstado()) {
                    mapa.put((EstadoSolicitud) fila[0], ((Number) fila[1]).longValue());
                }
                snapshot = new Snapshot(ahora.plus(cacheTtl), mapa);
                return mapa;
            } catch (RuntimeException e) {
                log.warn("No se pudo actualizar el gauge de solicitudes por estado: {}", e.getMessage());
                return null;
            }
        }
    }
}

package ar.org.refugio.adopciones.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tarea programada que vigila los plazos de entrega vencidos (R-09): una solicitud APROBADA
 * que no se entrega dentro del plazo se cancela y el animal vuelve a DISPONIBLE, para que no
 * quede reservado indefinidamente.
 *
 * Ejecuta una transaccion por solicitud (un fallo no aborta el lote), usa ShedLock sobre JDBC
 * para evitar ejecuciones duplicadas en multiples replicas y registra metricas de procesadas
 * y fallidas.
 */
@Component
@ConditionalOnProperty(name = "adopciones.tareas.habilitadas", havingValue = "true", matchIfMissing = true)
public class TareaVencimientos {

    private static final Logger log = LoggerFactory.getLogger(TareaVencimientos.class);

    private final SolicitudService solicitudService;
    private final Counter vencimientosProcesados;
    private final Counter vencimientosFallidos;

    public TareaVencimientos(SolicitudService solicitudService) {
        this(solicitudService, new SimpleMeterRegistry());
    }

    @Autowired
    public TareaVencimientos(SolicitudService solicitudService, MeterRegistry meterRegistry) {
        this.solicitudService = solicitudService;
        this.vencimientosProcesados = Counter.builder("adopciones.vencimientos.procesados")
                .description("Total de entregas vencidas canceladas automaticamente (R-09)")
                .register(meterRegistry);
        this.vencimientosFallidos = Counter.builder("adopciones.vencimientos.fallidos")
                .description("Total de errores al intentar cancelar entregas vencidas (R-09)")
                .register(meterRegistry);
    }

    @Scheduled(initialDelayString = "${adopciones.tareas.demora-inicial-ms:60000}",
            fixedDelayString = "${adopciones.tareas.intervalo-ms:300000}")
    @SchedulerLock(name = "vencerEntregas", lockAtLeastFor = "PT30S", lockAtMostFor = "PT4M")
    public void vencerEntregas() {
        List<Long> candidatas = solicitudService.buscarEntregasVencidas();
        int vencidas = 0;
        for (Long id : candidatas) {
            try {
                if (solicitudService.vencerEntrega(id)) {
                    vencidas++;
                    vencimientosProcesados.increment();
                }
            } catch (RuntimeException e) {
                vencimientosFallidos.increment();
                log.warn("No se pudo vencer la solicitud {} ({}); se reintenta en la proxima ejecucion", id, e.getMessage());
            }
        }
        if (!candidatas.isEmpty()) {
            log.info("Vencimientos de entrega: {} candidatas, {} canceladas", candidatas.size(), vencidas);
        }
    }
}

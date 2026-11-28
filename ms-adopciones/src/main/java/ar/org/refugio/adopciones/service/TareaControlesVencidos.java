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
 * Tarea programada (F1): detecta solicitudes EN_ADAPTACION cuyo control final (R-11) ya vencio
 * y registra una unica alerta en el historial sin cambiar el estado de la solicitud.
 */
@Component
@ConditionalOnProperty(name = "adopciones.tareas.habilitadas", havingValue = "true", matchIfMissing = true)
public class TareaControlesVencidos {

    private static final Logger log = LoggerFactory.getLogger(TareaControlesVencidos.class);

    private final SolicitudService solicitudService;
    private final Counter controlesAlertados;
    private final Counter controlesFallidos;

    public TareaControlesVencidos(SolicitudService solicitudService) {
        this(solicitudService, new SimpleMeterRegistry());
    }

    @Autowired
    public TareaControlesVencidos(SolicitudService solicitudService, MeterRegistry meterRegistry) {
        this.solicitudService = solicitudService;
        this.controlesAlertados = Counter.builder("adopciones.controles.vencidos.alertados")
                .description("Total de alertas registradas por controles finales vencidos (R-11)")
                .register(meterRegistry);
        this.controlesFallidos = Counter.builder("adopciones.controles.vencidos.fallidos")
                .description("Total de errores al registrar alertas de controles finales vencidos")
                .register(meterRegistry);
    }

    @Scheduled(initialDelayString = "${adopciones.tareas.demora-inicial-ms:60000}",
            fixedDelayString = "${adopciones.tareas.controles-intervalo-ms:300000}")
    @SchedulerLock(name = "alertarControlesVencidos", lockAtLeastFor = "PT30S", lockAtMostFor = "PT4M")
    public void alertarControlesVencidos() {
        List<Long> candidatas = solicitudService.buscarControlesVencidosSinAlertar();
        int alertadas = 0;
        for (Long id : candidatas) {
            try {
                if (solicitudService.alertarControlVencido(id)) {
                    alertadas++;
                    controlesAlertados.increment();
                }
            } catch (RuntimeException e) {
                controlesFallidos.increment();
                log.warn("No se pudo alertar control final vencido de la solicitud {} ({})", id, e.getMessage());
            }
        }
        if (!candidatas.isEmpty()) {
            log.info("Controles finales vencidos: {} candidatas, {} alertadas", candidatas.size(), alertadas);
        }
    }
}

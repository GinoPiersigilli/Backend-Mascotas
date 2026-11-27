package utnfc.isi.back.tpi.donaciones.service;

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
 * Tarea programada (F2): reconcilia periodicamente donaciones PENDIENTE antiguas consultando
 * Mercado Pago, complementando al webhook y a la verificacion bajo demanda (D-02/D-03).
 * Protegida por ShedLock sobre JDBC (donaciones.shedlock).
 */
@Component
@ConditionalOnProperty(name = "donaciones.tareas.habilitadas", havingValue = "true", matchIfMissing = true)
public class TareaReconciliacionDonaciones {

    private static final Logger log = LoggerFactory.getLogger(TareaReconciliacionDonaciones.class);

    private final DonacionService donacionService;
    private final Counter confirmadasCounter;
    private final Counter fallidasCounter;

    public TareaReconciliacionDonaciones(DonacionService donacionService) {
        this(donacionService, new SimpleMeterRegistry());
    }

    @Autowired
    public TareaReconciliacionDonaciones(DonacionService donacionService, MeterRegistry meterRegistry) {
        this.donacionService = donacionService;
        this.confirmadasCounter = Counter.builder("donaciones.reconciliacion.confirmadas")
                .description("Total de donaciones confirmadas por reconciliacion programada")
                .register(meterRegistry);
        this.fallidasCounter = Counter.builder("donaciones.reconciliacion.fallidas")
                .description("Total de errores durante la reconciliacion programada de donaciones")
                .register(meterRegistry);
    }

    @Scheduled(initialDelayString = "${donaciones.tareas.demora-inicial-ms:60000}",
            fixedDelayString = "${donaciones.tareas.intervalo-ms:300000}")
    @SchedulerLock(name = "reconciliarDonacionesPendientes", lockAtLeastFor = "PT30S", lockAtMostFor = "PT4M")
    public void reconciliarPendientes() {
        List<Long> candidatas = donacionService.buscarPendientesParaReconciliar();
        int confirmadas = 0;
        for (Long id : candidatas) {
            try {
                if (donacionService.reconciliarPendiente(id)) {
                    confirmadas++;
                    confirmadasCounter.increment();
                }
            } catch (RuntimeException e) {
                fallidasCounter.increment();
                log.warn("No se pudo reconciliar la donacion {} ({}); se reintenta en la proxima corrida",
                        id, e.getMessage());
            }
        }
        if (!candidatas.isEmpty()) {
            log.info("Reconciliacion de donaciones pendientes: {} candidatas, {} confirmadas",
                    candidatas.size(), confirmadas);
        }
    }
}

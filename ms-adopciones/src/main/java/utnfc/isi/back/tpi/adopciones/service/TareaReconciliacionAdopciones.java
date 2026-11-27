package utnfc.isi.back.tpi.adopciones.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import utnfc.isi.back.tpi.adopciones.dto.ReconciliacionResponse;

/**
 * Ejecucion programada del reconciliador ADR-06 (F3), protegida por ShedLock sobre JDBC.
 */
@Component
@ConditionalOnProperty(name = "adopciones.tareas.habilitadas", havingValue = "true", matchIfMissing = true)
public class TareaReconciliacionAdopciones {

    private static final Logger log = LoggerFactory.getLogger(TareaReconciliacionAdopciones.class);

    private final ReconciliadorAdopciones reconciliador;

    public TareaReconciliacionAdopciones(ReconciliadorAdopciones reconciliador) {
        this.reconciliador = reconciliador;
    }

    @Scheduled(initialDelayString = "${adopciones.tareas.demora-inicial-ms:60000}",
            fixedDelayString = "${adopciones.tareas.reconciliacion-intervalo-ms:600000}")
    @SchedulerLock(name = "reconciliarEstadosAnimales", lockAtLeastFor = "PT30S", lockAtMostFor = "PT4M")
    public void ejecutar() {
        ReconciliacionResponse res = reconciliador.reconciliar();
        if (res.reparadas() > 0 || res.conflictos() > 0 || res.erroresRemotos() > 0) {
            log.info("Reconciliacion programada ADR-06: evaluadas={}, reparadas={}, conflictos={}, erroresRemotos={}",
                    res.evaluadas(), res.reparadas(), res.conflictos(), res.erroresRemotos());
        }
    }
}

package utnfc.isi.back.tpi.adopciones.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import utnfc.isi.back.tpi.adopciones.client.AnimalDto;
import utnfc.isi.back.tpi.adopciones.client.AnimalesClient;
import utnfc.isi.back.tpi.adopciones.client.EstadoAnimal;
import utnfc.isi.back.tpi.adopciones.dto.ReconciliacionResponse;
import utnfc.isi.back.tpi.adopciones.dto.ReconciliacionResponse.DetalleInconsistencia;
import utnfc.isi.back.tpi.adopciones.model.EstadoSolicitud;
import utnfc.isi.back.tpi.adopciones.model.Solicitud;
import utnfc.isi.back.tpi.adopciones.repository.SolicitudRepository;
import utnfc.isi.back.tpi.common.exception.ConflictoException;
import utnfc.isi.back.tpi.common.exception.RecursoNoEncontradoException;
import utnfc.isi.back.tpi.common.exception.ServicioNoDisponibleException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciliador de estados entre ms-adopciones y ms-animales (F3 / ADR-06).
 *
 * Detecta y repara de forma idempotente las inconsistencias residuales tipicas de ADR-06
 * (por ejemplo: ms-animales confirmo RESERVADO y luego fallo el commit local en ms-adopciones,
 * dejando la solicitud PENDIENTE). Las desincronizaciones ambiguas no se pisan: se informan
 * como conflictos para revision del Coordinador.
 */
@Service
public class ReconciliadorAdopciones {

    private static final Logger log = LoggerFactory.getLogger(ReconciliadorAdopciones.class);
    private static final int VENTANA_FINALIZADAS_DIAS = 30;

    private final SolicitudRepository solicitudRepository;
    private final AnimalesClient animalesClient;
    private final TransactionTemplate txReadOnly;
    private final Clock clock;
    private final Counter reparadasCounter;
    private final Counter conflictosCounter;

    public ReconciliadorAdopciones(SolicitudRepository solicitudRepository, AnimalesClient animalesClient,
                                   PlatformTransactionManager transactionManager, Clock clock,
                                   MeterRegistry meterRegistry) {
        this.solicitudRepository = solicitudRepository;
        this.animalesClient = animalesClient;
        this.txReadOnly = new TransactionTemplate(transactionManager);
        this.txReadOnly.setReadOnly(true);
        this.clock = clock;
        this.reparadasCounter = Counter.builder("adopciones.reconciliacion.reparadas")
                .description("Total de inconsistencias Solicitud/Animal reparadas automaticamente (ADR-06)")
                .register(meterRegistry);
        this.conflictosCounter = Counter.builder("adopciones.reconciliacion.conflictos")
                .description("Total de inconsistencias Solicitud/Animal que requieren revision manual (ADR-06)")
                .register(meterRegistry);
    }

    public ReconciliacionResponse reconciliar() {
        List<Candidata> candidatas = txReadOnly.execute(st -> cargarCandidatas());
        if (candidatas == null || candidatas.isEmpty()) {
            return new ReconciliacionResponse(0, 0, 0, 0, List.of());
        }

        animalesClient.asegurarToken();
        int reparadas = 0;
        int conflictos = 0;
        int erroresRemotos = 0;
        List<DetalleInconsistencia> detalles = new ArrayList<>();

        for (Candidata c : candidatas) {
            EstadoAnimal esperado = estadoEsperado(c.estado());
            AnimalDto animal;
            try {
                animal = animalesClient.obtener(c.animalId());
            } catch (ServicioNoDisponibleException | RecursoNoEncontradoException e) {
                erroresRemotos++;
                detalles.add(new DetalleInconsistencia(c.solicitudId(), c.animalId(), c.estado(),
                        null, esperado, "ERROR_REMOTO", e.getMessage()));
                log.warn("Reconciliacion: no se pudo consultar el animal {} para solicitud {}: {}",
                        c.animalId(), c.solicitudId(), e.getMessage());
                continue;
            }

            if (animal.estado() == esperado) {
                continue;
            }

            if (esAutoReparable(c.estado(), animal.estado())) {
                try {
                    animalesClient.cambiarEstado(c.animalId(), esperado, c.solicitudId());
                    reparadas++;
                    reparadasCounter.increment();
                    String detalle = "Estado del animal reparado de " + animal.estado() + " a " + esperado;
                    detalles.add(new DetalleInconsistencia(c.solicitudId(), c.animalId(), c.estado(),
                            animal.estado(), esperado, "REPARADA", detalle));
                    log.warn("Reconciliacion ADR-06 REPARADA: solicitud={} ({}) animal={} {} -> {}",
                            c.solicitudId(), c.estado(), c.animalId(), animal.estado(), esperado);
                } catch (ConflictoException e) {
                    conflictos++;
                    conflictosCounter.increment();
                    detalles.add(new DetalleInconsistencia(c.solicitudId(), c.animalId(), c.estado(),
                            animal.estado(), esperado, "CONFLICTO", e.getMessage()));
                    log.warn("Reconciliacion ADR-06 CONFLICTO al reparar solicitud={} animal={}: {}",
                            c.solicitudId(), c.animalId(), e.getMessage());
                } catch (ServicioNoDisponibleException e) {
                    erroresRemotos++;
                    detalles.add(new DetalleInconsistencia(c.solicitudId(), c.animalId(), c.estado(),
                            animal.estado(), esperado, "ERROR_REMOTO", e.getMessage()));
                    log.warn("Reconciliacion ADR-06 ERROR_REMOTO al reparar solicitud={} animal={}: {}",
                            c.solicitudId(), c.animalId(), e.getMessage());
                }
            } else {
                conflictos++;
                conflictosCounter.increment();
                String detalle = "Desincronizacion no auto-reparable (requiere revision manual): solicitud "
                        + c.estado() + " con animal en " + animal.estado();
                detalles.add(new DetalleInconsistencia(c.solicitudId(), c.animalId(), c.estado(),
                        animal.estado(), esperado, "CONFLICTO", detalle));
                log.warn("Reconciliacion ADR-06 CONFLICTO: solicitud={} ({}) animal={} detectado={} esperado={}",
                        c.solicitudId(), c.estado(), c.animalId(), animal.estado(), esperado);
            }
        }

        return new ReconciliacionResponse(candidatas.size(), reparadas, conflictos, erroresRemotos, List.copyOf(detalles));
    }

    private List<Candidata> cargarCandidatas() {
        List<Solicitud> activas = solicitudRepository.findByEstadoInOrderByIdAsc(EstadoSolicitud.activas());
        Set<Long> animalesConProcesoAvanzado = activas.stream()
                .filter(s -> s.getEstado() == EstadoSolicitud.APROBADA || s.getEstado() == EstadoSolicitud.EN_ADAPTACION)
                .map(Solicitud::getAnimalId)
                .collect(Collectors.toSet());
        Set<Long> animalesActivos = activas.stream().map(Solicitud::getAnimalId).collect(Collectors.toSet());

        List<Candidata> resultado = new ArrayList<>();
        for (Solicitud s : activas) {
            if (s.getEstado() == EstadoSolicitud.PENDIENTE && animalesConProcesoAvanzado.contains(s.getAnimalId())) {
                continue;
            }
            resultado.add(new Candidata(s.getId(), s.getAnimalId(), s.getEstado()));
        }

        LocalDateTime desde = LocalDateTime.now(clock).minusDays(VENTANA_FINALIZADAS_DIAS);
        for (Solicitud s : solicitudRepository.findByEstadoAndFechaControlAfterOrderByIdAsc(EstadoSolicitud.FINALIZADA, desde)) {
            if (!animalesActivos.contains(s.getAnimalId())) {
                resultado.add(new Candidata(s.getId(), s.getAnimalId(), s.getEstado()));
            }
        }
        return resultado;
    }

    private static EstadoAnimal estadoEsperado(EstadoSolicitud estadoSolicitud) {
        return switch (estadoSolicitud) {
            case PENDIENTE -> EstadoAnimal.DISPONIBLE;
            case APROBADA -> EstadoAnimal.RESERVADO;
            case EN_ADAPTACION -> EstadoAnimal.EN_ADAPTACION;
            case FINALIZADA -> EstadoAnimal.ADOPTADO;
            default -> EstadoAnimal.DISPONIBLE;
        };
    }

    private static boolean esAutoReparable(EstadoSolicitud estadoSolicitud, EstadoAnimal estadoAnimal) {
        return switch (estadoSolicitud) {
            case PENDIENTE -> estadoAnimal == EstadoAnimal.RESERVADO;
            case APROBADA -> estadoAnimal == EstadoAnimal.DISPONIBLE;
            case EN_ADAPTACION -> estadoAnimal == EstadoAnimal.RESERVADO;
            case FINALIZADA -> estadoAnimal == EstadoAnimal.EN_ADAPTACION;
            default -> false;
        };
    }

    private record Candidata(Long solicitudId, Long animalId, EstadoSolicitud estado) {
    }
}

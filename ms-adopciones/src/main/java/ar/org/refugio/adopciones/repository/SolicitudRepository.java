package ar.org.refugio.adopciones.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ar.org.refugio.adopciones.model.EstadoSolicitud;
import ar.org.refugio.adopciones.model.Solicitud;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface SolicitudRepository extends JpaRepository<Solicitud, Long> {

    /** R-02: el adoptante ya tiene una solicitud activa. */
    boolean existsByAdoptanteIdAndEstadoIn(String adoptanteId, Collection<EstadoSolicitud> estados);

    /** R-01: el animal ya tiene una solicitud PENDIENTE. */
    boolean existsByAnimalIdAndEstado(Long animalId, EstadoSolicitud estado);

    List<Solicitud> findByAdoptanteIdOrderByIdDesc(String adoptanteId);

    List<Solicitud> findByAdoptanteIdAndEstadoOrderByIdDesc(String adoptanteId, EstadoSolicitud estado);

    List<Solicitud> findAllByOrderByIdDesc();

    List<Solicitud> findByEstadoOrderByIdDesc(EstadoSolicitud estado);

    Page<Solicitud> findByAdoptanteId(String adoptanteId, Pageable pageable);

    Page<Solicitud> findByAdoptanteIdAndEstado(String adoptanteId, EstadoSolicitud estado, Pageable pageable);

    Page<Solicitud> findByEstado(EstadoSolicitud estado, Pageable pageable);

    /** F1: solicitudes EN_ADAPTACION con control final vencido (filtro de Coordinador). */
    Page<Solicitud> findByEstadoAndFechaControlBefore(EstadoSolicitud estado, LocalDateTime limite, Pageable pageable);

    /** F1: candidatas a alerta de control final vencido aun no alertadas. */
    List<Solicitud> findByEstadoAndFechaControlBeforeAndControlVencidoAlertadoFalse(
            EstadoSolicitud estado, LocalDateTime limite);

    long countByEstadoAndFechaControlBefore(EstadoSolicitud estado, LocalDateTime limite);

    /** Candidatas a vencimiento de plazo de entrega (R-09), para la tarea programada. */
    List<Solicitud> findByEstadoAndFechaAprobacionBefore(EstadoSolicitud estado, LocalDateTime limite);

    /** F3: candidatas activas y finalizadas recientes para reconciliacion con ms-animales. */
    List<Solicitud> findByEstadoInOrderByIdAsc(Collection<EstadoSolicitud> estados);

    List<Solicitud> findByEstadoAndFechaControlAfterOrderByIdAsc(EstadoSolicitud estado, LocalDateTime desde);

    /** F4: agregacion de cantidad por estado sin hidratar entidades. */
    @Query("select s.estado, count(s) from Solicitud s group by s.estado")
    List<Object[]> contarPorEstado();

    /** F4: inicio y cierre efectivo de solicitudes resueltas para calcular promedio de resolucion. */
    @Query("""
            select s.fechaCreacion, max(e.fecha)
            from Solicitud s
            join s.eventos e
            where s.estado in :estadosFinales
            group by s.id, s.fechaCreacion
            """)
    List<Object[]> tiemposDeResolucion(@Param("estadosFinales") Collection<EstadoSolicitud> estadosFinales);
}

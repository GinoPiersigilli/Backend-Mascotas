package utnfc.isi.back.tpi.adopciones.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import utnfc.isi.back.tpi.adopciones.client.EstadoAnimal;
import utnfc.isi.back.tpi.adopciones.model.EstadoSolicitud;

import java.util.List;

/** Resultado de una corrida de reconciliacion entre ms-adopciones y ms-animales (ADR-06 / F3). */
public record ReconciliacionResponse(
        @Schema(example = "3", description = "Cantidad de solicitudes evaluadas contra ms-animales")
        int evaluadas,
        @Schema(example = "1", description = "Desincronizaciones reparadas automaticamente")
        int reparadas,
        @Schema(example = "0", description = "Desincronizaciones no auto-reparables que requieren revision manual")
        int conflictos,
        @Schema(example = "0", description = "Solicitudes que no pudieron verificarse por error remoto de ms-animales")
        int erroresRemotos,
        @Schema(description = "Detalle de inconsistencias detectadas")
        List<DetalleInconsistencia> detalles) {

    public record DetalleInconsistencia(
            @Schema(example = "5") Long solicitudId,
            @Schema(example = "1") Long animalId,
            @Schema(example = "PENDIENTE") EstadoSolicitud estadoSolicitud,
            @Schema(example = "RESERVADO", nullable = true) EstadoAnimal estadoAnimalDetectado,
            @Schema(example = "DISPONIBLE", nullable = true) EstadoAnimal estadoAnimalEsperado,
            @Schema(example = "REPARADA") String resultado,
            @Schema(example = "Animal liberado a DISPONIBLE por solicitud PENDIENTE sin reserva confirmada") String detalle) {
    }
}

package utnfc.isi.back.tpi.adopciones.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import utnfc.isi.back.tpi.adopciones.model.EstadoSolicitud;

import java.util.Map;

/** Resumen operativo de solicitudes para el Coordinador (F4). */
public record ResumenSolicitudesResponse(
        @Schema(example = "12", description = "Cantidad total de solicitudes registradas")
        long total,
        @Schema(description = "Cantidad de solicitudes agrupadas por estado")
        Map<EstadoSolicitud, Long> porEstado,
        @Schema(example = "2", description = "Solicitudes EN_ADAPTACION con fechaControl vencida")
        long controlesFinalesVencidos,
        @Schema(example = "18.5", nullable = true,
                description = "Horas promedio desde fechaCreacion hasta el cierre en solicitudes resueltas (FINALIZADA, RECHAZADA, CANCELADA, REVERTIDA)")
        Double tiempoPromedioResolucionHoras) {
}

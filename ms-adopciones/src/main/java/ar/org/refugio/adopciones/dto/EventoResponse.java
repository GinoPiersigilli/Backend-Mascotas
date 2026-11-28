package ar.org.refugio.adopciones.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ar.org.refugio.adopciones.model.EstadoSolicitud;
import ar.org.refugio.adopciones.model.EventoSolicitud;

import java.time.LocalDateTime;

/** Una entrada del historial de la solicitud. */
public record EventoResponse(
        @Schema(example = "2026-10-27T13:00:00") LocalDateTime fecha,
        @Schema(example = "PENDIENTE", nullable = true) EstadoSolicitud estadoAnterior,
        @Schema(example = "APROBADA") EstadoSolicitud estadoNuevo,
        @Schema(example = "coord-1") String actor,
        @Schema(example = "Solicitud aprobada") String detalle) {

    public static EventoResponse desde(EventoSolicitud e) {
        return new EventoResponse(e.getFecha(), e.getEstadoAnterior(), e.getEstadoNuevo(), e.getActor(), e.getDetalle());
    }
}

package ar.org.refugio.adopciones.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ar.org.refugio.adopciones.model.EstadoSolicitud;
import ar.org.refugio.adopciones.model.ResultadoControl;
import ar.org.refugio.adopciones.model.Solicitud;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Representacion de una solicitud (CONTRATOS.md, seccion 7) con el resultado de las
 * verificaciones automaticas. No incluye la direccion del adoptante.
 * Agrega a lo acordado animalNombre, observacionesControl y motivoCierre (trazabilidad).
 */
public record SolicitudResponse(
        @Schema(example = "1") Long id,
        @Schema(example = "1") Long animalId,
        @Schema(example = "Luna") String animalNombre,
        @Schema(example = "adoptante-sub-1") String adoptanteId,
        @Schema(example = "PENDIENTE") EstadoSolicitud estado,
        @Schema(example = "2026-10-27T13:00:00") LocalDateTime fechaCreacion,
        @Schema(example = "2.7", nullable = true) Double distanciaKm,
        @Schema(example = "false") boolean requiereExcepcion,
        @Schema(example = "false") boolean distanciaNoVerificada,
        List<String> observaciones,
        @Schema(nullable = true) String motivoRechazo,
        @Schema(nullable = true) String justificacionExcepcion,
        @Schema(nullable = true) LocalDateTime fechaAprobacion,
        @Schema(nullable = true) LocalDateTime fechaEntrega,
        @Schema(nullable = true) LocalDateTime fechaControl,
        @Schema(nullable = true) ResultadoControl resultadoControl,
        @Schema(nullable = true) String observacionesControl,
        @Schema(nullable = true) String motivoCierre) {

    public static SolicitudResponse desde(Solicitud s) {
        return new SolicitudResponse(s.getId(), s.getAnimalId(), s.getAnimalNombre(), s.getAdoptanteId(),
                s.getEstado(), s.getFechaCreacion(), s.getDistanciaKm(), s.isRequiereExcepcion(),
                s.isDistanciaNoVerificada(), List.copyOf(s.getObservaciones()), s.getMotivoRechazo(),
                s.getJustificacionExcepcion(), s.getFechaAprobacion(), s.getFechaEntrega(), s.getFechaControl(),
                s.getResultadoControl(), s.getObservacionesControl(), s.getMotivoCierre());
    }
}

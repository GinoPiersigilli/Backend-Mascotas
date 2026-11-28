package ar.org.refugio.adopciones.event;

import java.time.LocalDateTime;

/** Evento de dominio publicado tras confirmar la aprobacion y reserva en ms-animales. */
public record SolicitudAprobadaEvent(
        Long solicitudId,
        Long animalId,
        String adoptanteId,
        String actor,
        boolean conExcepcion,
        LocalDateTime fecha) {
}

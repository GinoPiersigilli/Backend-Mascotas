package ar.org.refugio.adopciones.event;

import java.time.LocalDateTime;

/** Evento de dominio publicado cuando el control final resulta CONFORME y la adopcion finaliza. */
public record AdopcionConfirmadaEvent(
        Long solicitudId,
        Long animalId,
        String adoptanteId,
        String actor,
        LocalDateTime fecha) {
}

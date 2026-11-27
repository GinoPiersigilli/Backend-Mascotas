package utnfc.isi.back.tpi.adopciones.event;

import java.time.LocalDateTime;

/** Evento de dominio publicado tras confirmar la entrega y pasar a EN_ADAPTACION. */
public record EntregaConfirmadaEvent(
        Long solicitudId,
        Long animalId,
        String adoptanteId,
        String actor,
        LocalDateTime fechaEntrega,
        LocalDateTime fechaControl) {
}

package ar.org.refugio.animales.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import ar.org.refugio.animales.model.EstadoAnimal;

/** Cuerpo de la API interna: estado destino y solicitudId opcional para idempotencia de reintentos. */
public record CambioEstadoRequest(
        @Schema(example = "RESERVADO")
        @NotNull(message = "es obligatorio")
        EstadoAnimal nuevoEstado,

        @Schema(description = "Identificador opcional de la solicitud para transiciones idempotentes", example = "42", nullable = true)
        @Positive(message = "debe ser positivo")
        Long solicitudId) {

    public CambioEstadoRequest(EstadoAnimal nuevoEstado) {
        this(nuevoEstado, null);
    }
}

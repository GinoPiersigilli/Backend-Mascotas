package ar.org.refugio.donaciones.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import ar.org.refugio.donaciones.model.Donacion;
import ar.org.refugio.donaciones.model.EstadoDonacion;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** DTO de la API de donaciones (CONTRATOS.md, seccion 8). */
public final class DonacionDtos {

    private DonacionDtos() {
    }

    public record CrearDonacion(
            @Schema(example = "500")
            @NotNull(message = "es obligatorio") @Positive(message = "debe ser mayor a 0")
            @Digits(integer = 10, fraction = 2, message = "admite hasta 10 enteros y 2 decimales")
            BigDecimal monto) {
    }

    /**
     * verificado: true si el estado devuelto refleja lo informado por Mercado Pago (se consulto
     * con exito en esta llamada, o la donacion ya esta CONFIRMADA, que es final). false si el
     * proveedor no pudo consultarse y el estado podria estar desactualizado.
     */
    public record DonacionResponse(
            @Schema(example = "1") Long id,
            @Schema(example = "adoptante-sub-1") String donanteId,
            @Schema(example = "500.00") BigDecimal monto,
            @Schema(example = "ARS") String moneda,
            @Schema(example = "PENDIENTE") EstadoDonacion estado,
            @Schema(example = "2026-10-27T13:00:00") LocalDateTime fechaCreacion,
            @Schema(example = "2026-10-27T13:00:00") LocalDateTime fechaActualizacion,
            @Schema(example = "https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=pref-1") String linkPago,
            @Schema(example = "true") boolean verificado) {

        public static DonacionResponse desde(Donacion d, boolean verificado) {
            return new DonacionResponse(d.getId(), d.getDonanteId(), d.getMonto(), d.getMoneda(), d.getEstado(),
                    d.getFechaCreacion(), d.getFechaActualizacion(), d.getLinkPago(), verificado);
        }
    }

    public record TotalResponse(
            @Schema(example = "1500.00") BigDecimal totalConfirmado,
            @Schema(example = "3") long cantidad) {
    }
}

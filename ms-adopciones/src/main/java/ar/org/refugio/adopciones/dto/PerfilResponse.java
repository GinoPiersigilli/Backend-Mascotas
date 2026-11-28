package ar.org.refugio.adopciones.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ar.org.refugio.adopciones.model.Perfil;
import ar.org.refugio.adopciones.model.TipoVivienda;

import java.time.LocalDateTime;

/**
 * Perfil completo, con la direccion exacta. Solo lo ven el propio adoptante y el Coordinador
 * (supuesto de privacidad del documento de dominio, seccion 3.5).
 */
public record PerfilResponse(
        @Schema(example = "adoptante-sub-1") String adoptanteId,
        @Schema(example = "Ana Perez") String nombreCompleto,
        @Schema(example = "3515550000") String telefono,
        @Schema(example = "Cordoba") String provincia,
        @Schema(example = "Cordoba") String localidad,
        @Schema(example = "Av Colon 1000") String direccion,
        @Schema(example = "CASA") TipoVivienda tipoVivienda,
        @Schema(example = "true") boolean tienePatio,
        @Schema(example = "false") boolean hayNinios,
        @Schema(example = "false") boolean hayOtrosAnimales,
        @Schema(example = "2026-10-27T13:00:00") LocalDateTime fechaActualizacion) {

    public static PerfilResponse desde(Perfil p) {
        return new PerfilResponse(p.getAdoptanteId(), p.getNombreCompleto(), p.getTelefono(), p.getProvincia(),
                p.getLocalidad(), p.getDireccion(), p.getTipoVivienda(), p.isTienePatio(), p.isHayNinios(),
                p.isHayOtrosAnimales(), p.getFechaActualizacion());
    }
}

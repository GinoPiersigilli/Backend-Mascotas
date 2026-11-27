package utnfc.isi.back.tpi.animales.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import utnfc.isi.back.tpi.animales.dto.AnimalResponse;
import utnfc.isi.back.tpi.animales.dto.CambioEstadoRequest;
import utnfc.isi.back.tpi.animales.service.AnimalService;
import utnfc.isi.back.tpi.common.error.ErrorResponse;

/**
 * API interna (CONTRATOS.md, seccion 6.2). Solo la usa el servicio de Adopciones con un token
 * de servicio (rol SERVICIO). El Gateway no tiene ruta hacia /internal/**, de modo que ningun
 * usuario puede ordenar cambios de estado del proceso de adopcion directamente.
 */
@RestController
@RequestMapping("/internal/animales")
@Tag(name = "Interna", description = "Uso exclusivo del servicio de Adopciones (rol SERVICIO)")
public class AnimalInternoController {

    private final AnimalService service;

    public AnimalInternoController(AnimalService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Estado y aptitudes del animal (R-01, R-04, R-05)")
    @ApiResponse(responseCode = "200", description = "Animal encontrado")
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Requiere rol SERVICIO",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe el animal",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public AnimalResponse obtener(@Parameter(example = "1") @PathVariable Long id) {
        return service.obtener(id);
    }

    @PutMapping("/{id}/estado")
    @Operation(summary = "Transicion de estado pedida por el proceso de adopcion")
    @ApiResponse(responseCode = "200", description = "Transicion aplicada")
    @ApiResponse(responseCode = "400", description = "Estado invalido",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Requiere rol SERVICIO",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe el animal",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "La transicion no es valida desde el estado actual o concurrencia",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public AnimalResponse cambiarEstado(@Parameter(example = "1") @PathVariable Long id,
                                        @Valid @RequestBody CambioEstadoRequest request) {
        return request.solicitudId() == null
                ? service.cambiarEstadoPorProceso(id, request.nuevoEstado())
                : service.cambiarEstadoPorProceso(id, request.nuevoEstado(), request.solicitudId());
    }
}

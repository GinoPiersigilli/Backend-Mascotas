package utnfc.isi.back.tpi.animales.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import utnfc.isi.back.tpi.animales.dto.AnimalRequest;
import utnfc.isi.back.tpi.animales.dto.AnimalResponse;
import utnfc.isi.back.tpi.animales.dto.MotivoRequest;
import utnfc.isi.back.tpi.animales.model.EstadoAnimal;
import utnfc.isi.back.tpi.animales.model.Especie;
import utnfc.isi.back.tpi.animales.service.AnimalService;
import utnfc.isi.back.tpi.common.error.ErrorResponse;

import java.net.URI;
import java.util.List;

/**
 * API publica del catalogo (se publica por el Gateway en /api/animales/**).
 * Los permisos por rol estan en SecurityConfig.
 */
@RestController
@RequestMapping("/api/animales")
@Tag(name = "Animales", description = "Catalogo de animales y su disponibilidad (P1)")
public class AnimalController {

    private final AnimalService service;

    public AnimalController(AnimalService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Alta de un animal (COORDINADOR). Queda DISPONIBLE")
    @ApiResponse(responseCode = "201", description = "Animal creado")
    @ApiResponse(responseCode = "400", description = "Datos invalidos",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente (requiere COORDINADOR)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<AnimalResponse> crear(@Valid @RequestBody AnimalRequest request) {
        AnimalResponse creado = service.crear(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(creado.id()).toUri();
        return ResponseEntity.created(location).body(creado);
    }

    @GetMapping
    @Operation(summary = "Lista animales paginados, con filtros opcionales por estado y especie")
    @ApiResponse(responseCode = "200", description = "Lista de animales; el encabezado X-Total-Count informa el total",
            headers = @Header(name = "X-Total-Count", description = "Cantidad total de registros", schema = @Schema(type = "integer")))
    @ApiResponse(responseCode = "400", description = "Parametros de filtro o paginacion invalidos",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<List<AnimalResponse>> listar(
            @Parameter(description = "Filtrar por estado", example = "DISPONIBLE") @RequestParam(required = false) EstadoAnimal estado,
            @Parameter(description = "Filtrar por especie", example = "PERRO") @RequestParam(required = false) Especie especie,
            @Parameter(description = "Numero de pagina (base 0)", example = "0") @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @Parameter(description = "Cantidad de elementos por pagina (1 a 100)", example = "50") @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        Page<AnimalResponse> pagina = service.listar(estado, especie,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id")));
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(pagina.getTotalElements()))
                .body(pagina.getContent());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulta un animal")
    @ApiResponse(responseCode = "200", description = "Animal encontrado")
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe el animal",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public AnimalResponse obtener(@Parameter(example = "1") @PathVariable Long id) {
        return service.obtener(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Modifica los datos de un animal (COORDINADOR). No cambia su estado")
    @ApiResponse(responseCode = "200", description = "Animal actualizado")
    @ApiResponse(responseCode = "400", description = "Datos invalidos",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente (requiere COORDINADOR)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe el animal",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Modificacion concurrente (@Version)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public AnimalResponse actualizar(@Parameter(example = "1") @PathVariable Long id, @Valid @RequestBody AnimalRequest request) {
        return service.actualizar(id, request);
    }

    @PostMapping("/{id}/marcar-no-disponible")
    @Operation(summary = "Deja al animal NO_DISPONIBLE con motivo (COORDINADOR)")
    @ApiResponse(responseCode = "200", description = "Animal marcado como NO_DISPONIBLE")
    @ApiResponse(responseCode = "400", description = "Falta el motivo o es invalido",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente (requiere COORDINADOR)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe el animal",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Tiene una adopcion en curso (R-13) o no esta DISPONIBLE",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public AnimalResponse marcarNoDisponible(@Parameter(example = "1") @PathVariable Long id, @Valid @RequestBody MotivoRequest request) {
        return service.marcarNoDisponible(id, request.motivo());
    }

    @PostMapping("/{id}/marcar-disponible")
    @Operation(summary = "Devuelve a DISPONIBLE un animal NO_DISPONIBLE (COORDINADOR)")
    @ApiResponse(responseCode = "200", description = "Animal devuelto a DISPONIBLE")
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente (requiere COORDINADOR)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe el animal",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "El animal no esta NO_DISPONIBLE",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public AnimalResponse marcarDisponible(@Parameter(example = "1") @PathVariable Long id) {
        return service.marcarDisponible(id);
    }
}

package utnfc.isi.back.tpi.donaciones.controller;

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
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import utnfc.isi.back.tpi.common.error.ErrorResponse;
import utnfc.isi.back.tpi.common.security.Roles;
import utnfc.isi.back.tpi.donaciones.dto.DonacionDtos.CrearDonacion;
import utnfc.isi.back.tpi.donaciones.dto.DonacionDtos.DonacionResponse;
import utnfc.isi.back.tpi.donaciones.dto.DonacionDtos.TotalResponse;
import utnfc.isi.back.tpi.donaciones.service.DonacionService;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/donaciones")
@Tag(name = "Donaciones", description = "Donaciones a la cuenta comun con Mercado Pago (P5)")
public class DonacionController {

    private final DonacionService service;

    public DonacionController(DonacionService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Crea una donacion y devuelve el enlace de pago (ADOPTANTE)",
            description = "Admite el encabezado opcional Idempotency-Key para reintentos seguros sin cobrar dos veces (ADR-18)")
    @ApiResponse(responseCode = "201", description = "Donacion PENDIENTE con linkPago")
    @ApiResponse(responseCode = "400", description = "Monto menor al minimo (D-01) o Idempotency-Key invalido",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente (requiere ADOPTANTE)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Idempotency-Key reutilizada con un monto diferente (ADR-18)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "Mercado Pago no responde: no se crea la donacion",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<DonacionResponse> crear(
            JwtAuthenticationToken auth,
            @Parameter(description = "Clave opcional de idempotencia (max. 128 caracteres)", example = "550e8400-e29b-41d4-a716-446655440000")
            @RequestHeader(value = "Idempotency-Key", required = false)
            @Size(max = 128, message = "admite hasta 128 caracteres") String idempotencyKey,
            @Valid @RequestBody CrearDonacion request) {
        DonacionResponse creada = service.crear(auth.getToken().getSubject(), request.monto(), idempotencyKey);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(creada.id()).toUri();
        return ResponseEntity.created(location).body(creada);
    }

    @GetMapping
    @Operation(summary = "Lista paginada de donaciones propias (ADOPTANTE) o todas (COORDINADOR)")
    @ApiResponse(responseCode = "200", description = "Lista de donaciones; el encabezado X-Total-Count informa el total",
            headers = @Header(name = "X-Total-Count", description = "Cantidad total de registros", schema = @Schema(type = "integer")))
    @ApiResponse(responseCode = "400", description = "Parametros de paginacion invalidos",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<List<DonacionResponse>> listar(
            JwtAuthenticationToken auth,
            @Parameter(description = "Numero de pagina (base 0)", example = "0") @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @Parameter(description = "Cantidad de elementos por pagina (1 a 100)", example = "50") @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        Page<DonacionResponse> pagina = service.listar(auth.getToken().getSubject(), esCoordinador(auth),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(pagina.getTotalElements()))
                .body(pagina.getContent());
    }

    @GetMapping("/total")
    @Operation(summary = "Total confirmado y cantidad de donaciones confirmadas (COORDINADOR)")
    @ApiResponse(responseCode = "200", description = "Totales de donaciones confirmadas")
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente (requiere COORDINADOR)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public TotalResponse total() {
        return service.total();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulta una donacion; si esta PENDIENTE verifica el pago en Mercado Pago (D-02)")
    @ApiResponse(responseCode = "200", description = "Donacion encontrada (si Mercado Pago no responde, devuelve PENDIENTE con verificado=false)")
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Pertenece a otro donante (D-04)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe la donacion",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public DonacionResponse obtener(JwtAuthenticationToken auth, @Parameter(example = "1") @PathVariable Long id) {
        return service.obtener(auth.getToken().getSubject(), esCoordinador(auth), id);
    }

    private static boolean esCoordinador(JwtAuthenticationToken auth) {
        return Roles.tieneRol(auth, Roles.COORDINADOR);
    }
}

package ar.org.refugio.adopciones.controller;

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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ar.org.refugio.adopciones.dto.EventoResponse;
import ar.org.refugio.adopciones.dto.ReconciliacionResponse;
import ar.org.refugio.adopciones.dto.ResumenSolicitudesResponse;
import ar.org.refugio.adopciones.dto.SolicitudRequests;
import ar.org.refugio.adopciones.dto.SolicitudResponse;
import ar.org.refugio.adopciones.model.EstadoSolicitud;
import ar.org.refugio.adopciones.service.ReconciliadorAdopciones;
import ar.org.refugio.adopciones.service.SolicitudService;
import ar.org.refugio.common.error.ErrorResponse;

import java.net.URI;
import java.util.List;

/**
 * Proceso de adopcion de punta a punta (CONTRATOS.md, seccion 7). El controlador solo traduce
 * HTTP: las reglas estan en SolicitudService y en la entidad Solicitud; los roles, en SecurityConfig.
 */
@RestController
@RequestMapping("/api/solicitudes")
@Tag(name = "Solicitudes", description = "Presentacion, evaluacion, entrega y seguimiento (P2 a P4)")
public class SolicitudController {

    private final SolicitudService service;
    private final ReconciliadorAdopciones reconciliador;

    public SolicitudController(SolicitudService service, ReconciliadorAdopciones reconciliador) {
        this.service = service;
        this.reconciliador = reconciliador;
    }

    @PostMapping
    @Operation(summary = "Presenta una solicitud (ADOPTANTE). Aplica R-01 a R-06",
            description = "201 PENDIENTE con marcas, o 201 RECHAZADA si hay incompatibilidad dura (R-04)")
    @ApiResponse(responseCode = "201", description = "Solicitud registrada")
    @ApiResponse(responseCode = "400", description = "Datos invalidos",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente (requiere ADOPTANTE)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe el animal",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "R-01, R-02 o R-03",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "El servicio de Animales no responde",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<SolicitudResponse> crear(JwtAuthenticationToken auth,
                                                   @Valid @RequestBody SolicitudRequests.Crear request) {
        SolicitudResponse creada = service.crear(UsuarioActual.de(auth), request.animalId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(creada.id()).toUri();
        return ResponseEntity.created(location).body(creada);
    }

    @GetMapping
    @Operation(summary = "Lista solicitudes paginadas: las propias (ADOPTANTE) o todas (COORDINADOR)")
    @ApiResponse(responseCode = "200", description = "Lista de solicitudes; el encabezado X-Total-Count informa el total",
            headers = @Header(name = "X-Total-Count", description = "Cantidad total de registros", schema = @Schema(type = "integer")))
    @ApiResponse(responseCode = "400", description = "Parametros de filtro o paginacion invalidos",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<List<SolicitudResponse>> listar(
            JwtAuthenticationToken auth,
            @Parameter(description = "Filtrar por estado", example = "PENDIENTE") @RequestParam(required = false) EstadoSolicitud estado,
            @Parameter(description = "Filtrar solicitudes EN_ADAPTACION con control final vencido (solo COORDINADOR)", example = "true")
            @RequestParam(required = false) Boolean controlVencido,
            @Parameter(description = "Numero de pagina (base 0)", example = "0") @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @Parameter(description = "Cantidad de elementos por pagina (1 a 100)", example = "50") @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        Page<SolicitudResponse> pagina = service.listar(UsuarioActual.de(auth), estado, controlVencido,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(pagina.getTotalElements()))
                .body(pagina.getContent());
    }

    @GetMapping("/reportes")
    @Operation(summary = "Resumen de solicitudes por estado y tiempo promedio de resolucion (COORDINADOR)")
    @ApiResponse(responseCode = "200", description = "Resumen operativo de solicitudes")
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente (requiere COORDINADOR)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public ResumenSolicitudesResponse reportes() {
        return service.resumen();
    }

    @PostMapping("/reconciliacion")
    @Operation(summary = "Ejecuta una corrida de reconciliacion de estados contra ms-animales (COORDINADOR). ADR-06")
    @ApiResponse(responseCode = "200", description = "Resultado de la reconciliacion con reparaciones y conflictos")
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente (requiere COORDINADOR)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public ReconciliacionResponse reconciliar() {
        return reconciliador.reconciliar();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulta una solicitud (duenio o COORDINADOR)")
    @ApiResponse(responseCode = "200", description = "Solicitud encontrada")
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Pertenece a otro adoptante",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe la solicitud",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public SolicitudResponse obtener(JwtAuthenticationToken auth, @Parameter(example = "1") @PathVariable Long id) {
        return service.obtener(UsuarioActual.de(auth), id);
    }

    @GetMapping("/{id}/historial")
    @Operation(summary = "Historial de cambios de estado con fecha, actor y motivo (duenio o COORDINADOR)")
    @ApiResponse(responseCode = "200", description = "Historial de eventos")
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Pertenece a otro adoptante",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe la solicitud",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public List<EventoResponse> historial(JwtAuthenticationToken auth, @Parameter(example = "1") @PathVariable Long id) {
        return service.historial(UsuarioActual.de(auth), id);
    }

    @PostMapping("/{id}/cancelar")
    @Operation(summary = "El adoptante cancela su solicitud PENDIENTE")
    @ApiResponse(responseCode = "200", description = "Solicitud cancelada")
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Pertenece a otro adoptante o rol insuficiente",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe la solicitud",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "No esta PENDIENTE o modificacion concurrente",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public SolicitudResponse cancelar(JwtAuthenticationToken auth, @Parameter(example = "1") @PathVariable Long id) {
        return service.cancelar(UsuarioActual.de(auth), id);
    }

    @PostMapping("/{id}/aprobar")
    @Operation(summary = "Aprueba y reserva al animal (COORDINADOR). R-07, R-08")
    @ApiResponse(responseCode = "200", description = "Solicitud aprobada y animal reservado")
    @ApiResponse(responseCode = "400", description = "Datos invalidos",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente (requiere COORDINADOR)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe la solicitud o el animal",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "No esta PENDIENTE, falta justificacion, concurrencia o el animal no esta DISPONIBLE",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "Animales no responde: la solicitud sigue PENDIENTE",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public SolicitudResponse aprobar(JwtAuthenticationToken auth, @Parameter(example = "1") @PathVariable Long id,
                                     @Valid @RequestBody(required = false) SolicitudRequests.Aprobar request) {
        String justificacion = request == null ? null : request.justificacion();
        return service.aprobar(UsuarioActual.de(auth), id, justificacion);
    }

    @PostMapping("/{id}/rechazar")
    @Operation(summary = "Rechaza con motivo obligatorio (COORDINADOR). R-08")
    @ApiResponse(responseCode = "200", description = "Solicitud rechazada")
    @ApiResponse(responseCode = "400", description = "Falta el motivo o es invalido",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente (requiere COORDINADOR)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe la solicitud",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "No esta PENDIENTE o modificacion concurrente",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public SolicitudResponse rechazar(JwtAuthenticationToken auth, @Parameter(example = "1") @PathVariable Long id,
                                      @Valid @RequestBody SolicitudRequests.Motivo request) {
        return service.rechazar(UsuarioActual.de(auth), id, request.motivo());
    }

    @PostMapping("/{id}/confirmar-entrega")
    @Operation(summary = "Confirma la entrega; animal y solicitud pasan a EN_ADAPTACION (COORDINADOR). R-09, R-10")
    @ApiResponse(responseCode = "200", description = "Entrega confirmada")
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente (requiere COORDINADOR)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe la solicitud",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "No esta APROBADA o el plazo de entrega vencio (R-09)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "Animales no responde",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public SolicitudResponse confirmarEntrega(JwtAuthenticationToken auth, @Parameter(example = "1") @PathVariable Long id) {
        return service.confirmarEntrega(UsuarioActual.de(auth), id);
    }

    @PostMapping("/{id}/cancelar-entrega")
    @Operation(summary = "Cancela una entrega pendiente; el animal vuelve a DISPONIBLE (COORDINADOR)")
    @ApiResponse(responseCode = "200", description = "Entrega cancelada y animal liberado")
    @ApiResponse(responseCode = "400", description = "Falta el motivo o es invalido",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente (requiere COORDINADOR)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe la solicitud",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "No esta APROBADA o modificacion concurrente",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "Animales no responde",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public SolicitudResponse cancelarEntrega(JwtAuthenticationToken auth, @Parameter(example = "1") @PathVariable Long id,
                                             @Valid @RequestBody SolicitudRequests.Motivo request) {
        return service.cancelarEntrega(UsuarioActual.de(auth), id, request.motivo());
    }

    @PostMapping("/{id}/control-final")
    @Operation(summary = "Registra el control final (COORDINADOR). R-11, R-12")
    @ApiResponse(responseCode = "200", description = "Control final registrado")
    @ApiResponse(responseCode = "400", description = "Datos invalidos o faltan observaciones en control desfavorable",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Rol insuficiente (requiere COORDINADOR)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe la solicitud",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Anterior a la fecha de control o no esta EN_ADAPTACION",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "Animales no responde",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public SolicitudResponse controlFinal(JwtAuthenticationToken auth, @Parameter(example = "1") @PathVariable Long id,
                                          @Valid @RequestBody SolicitudRequests.ControlFinal request) {
        return service.registrarControl(UsuarioActual.de(auth), id, request.resultado(), request.observaciones());
    }

    @PostMapping("/{id}/devolucion")
    @Operation(summary = "El adoptante devuelve al animal durante la adaptacion. R-12")
    @ApiResponse(responseCode = "200", description = "Devolucion registrada y animal liberado")
    @ApiResponse(responseCode = "400", description = "Falta el motivo o es invalido",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "No autenticado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Pertenece a otro adoptante o rol insuficiente",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe la solicitud",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "No esta EN_ADAPTACION o modificacion concurrente",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "Animales no responde",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public SolicitudResponse devolucion(JwtAuthenticationToken auth, @Parameter(example = "1") @PathVariable Long id,
                                        @Valid @RequestBody SolicitudRequests.Motivo request) {
        return service.devolver(UsuarioActual.de(auth), id, request.motivo());
    }
}

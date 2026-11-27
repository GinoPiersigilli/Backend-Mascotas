package utnfc.isi.back.tpi.donaciones.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utnfc.isi.back.tpi.common.error.ErrorResponse;
import utnfc.isi.back.tpi.donaciones.service.DonacionService;
import utnfc.isi.back.tpi.donaciones.service.VerificadorFirmaMercadoPago;

import java.util.List;

/**
 * Webhook publico para notificaciones de Mercado Pago (F2).
 *
 * No usa JWT de Keycloak: verifica la autenticidad mediante firma HMAC-SHA256 en la cabecera
 * {@code x-signature} ({@link VerificadorFirmaMercadoPago}) y luego consulta el pago directamente
 * a Mercado Pago ({@code GET /v1/payments/{id}}) antes de confirmar la donacion.
 */
@RestController
@RequestMapping("/api/donaciones/webhooks/mercadopago")
@Tag(name = "Webhooks", description = "Notificaciones entrantes firmadas de Mercado Pago (F2)")
public class MercadoPagoWebhookController {

    private static final String RUTA = "/api/donaciones/webhooks/mercadopago";

    private final VerificadorFirmaMercadoPago verificador;
    private final DonacionService donacionService;

    public MercadoPagoWebhookController(VerificadorFirmaMercadoPago verificador, DonacionService donacionService) {
        this.verificador = verificador;
        this.donacionService = donacionService;
    }

    @PostMapping
    @Operation(
            summary = "Recibe notificaciones firmadas de pago desde Mercado Pago (sin JWT; valida x-signature)",
            description = "Valida HMAC-SHA256 con MP_WEBHOOK_SECRET y consulta el estado autoritativo del pago en Mercado Pago",
            security = {})
    @ApiResponse(responseCode = "200", description = "Notificacion recibida y procesada de forma idempotente")
    @ApiResponse(responseCode = "401", description = "Cabecera x-signature ausente o invalida",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "Secreto no configurado o Mercado Pago no responde (reintento seguro)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<?> recibir(
            @Parameter(description = "Firma HMAC-SHA256 enviada por Mercado Pago", example = "ts=1704908010,v1=...")
            @RequestHeader(value = "x-signature", required = false) String xSignature,
            @Parameter(description = "Identificador de solicitud enviado por Mercado Pago", example = "req-123")
            @RequestHeader(value = "x-request-id", required = false) String xRequestId,
            @Parameter(description = "Tipo de evento (query param)", example = "payment")
            @RequestParam(value = "type", required = false) String queryType,
            @Parameter(description = "Identificador del recurso (query param data.id)", example = "999888777")
            @RequestParam(value = "data.id", required = false) String queryDataId,
            @RequestBody(required = false) WebhookPayload body) {
        String dataId = primeroNoVacio(queryDataId, body != null && body.data() != null ? body.data().id() : null);
        if (!verificador.esValida(xSignature, xRequestId, dataId)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Firma del webhook invalida o ausente", RUTA, List.of()));
        }

        String tipo = primeroNoVacio(queryType, body != null ? body.type() : null);
        String accion = body != null ? body.action() : null;
        boolean esPago = "payment".equalsIgnoreCase(tipo)
                || (accion != null && accion.toLowerCase().startsWith("payment."));

        if (!esPago || dataId == null) {
            return ResponseEntity.ok(new WebhookAckResponse(false, "IGNORADO"));
        }

        boolean confirmada = donacionService.procesarNotificacionPago(dataId);
        return ResponseEntity.ok(new WebhookAckResponse(confirmada, confirmada ? "CONFIRMADA" : "SIN_CAMBIOS"));
    }

    private static String primeroNoVacio(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return (b != null && !b.isBlank()) ? b.trim() : null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WebhookPayload(
            @Schema(example = "payment") String type,
            @Schema(example = "payment.updated") String action,
            WebhookData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WebhookData(@Schema(example = "999888777") String id) {
    }

    public record WebhookAckResponse(
            @Schema(example = "true") boolean confirmada,
            @Schema(example = "CONFIRMADA") String resultado) {
    }
}

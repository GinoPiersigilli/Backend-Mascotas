package ar.org.refugio.donaciones.service;

import org.springframework.stereotype.Component;
import ar.org.refugio.common.exception.ServicioNoDisponibleException;
import ar.org.refugio.donaciones.config.DonacionesProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Valida la cabecera {@code x-signature} de los webhooks de Mercado Pago usando HMAC-SHA256
 * segun la especificacion oficial:
 * {@code x-signature: ts=<timestamp>,v1=<hex_hmac_sha256>} sobre el manifiesto
 * {@code id:<data.id_lowercase>;request-id:<x-request-id>;ts:<ts>;}.
 *
 * Compara en tiempo constante ({@link MessageDigest#isEqual}) y rechaza por defecto si el secreto
 * no fue configurado ({@code MP_WEBHOOK_SECRET}).
 */
@Component
public class VerificadorFirmaMercadoPago {

    private static final String ALGORITMO = "HmacSHA256";
    private static final String SIN_CONFIGURAR = "sin-configurar";

    private final DonacionesProperties props;

    public VerificadorFirmaMercadoPago(DonacionesProperties props) {
        this.props = props;
    }

    public boolean esValida(String xSignature, String xRequestId, String dataId) {
        String secreto = props.mercadoPago().webhookSecret();
        if (secreto == null || secreto.isBlank() || SIN_CONFIGURAR.equals(secreto.trim())) {
            throw new ServicioNoDisponibleException("El secreto de webhook de Mercado Pago no esta configurado");
        }
        if (xSignature == null || xSignature.isBlank()) {
            return false;
        }
        String ts = null;
        String v1 = null;
        for (String parte : xSignature.split(",")) {
            String[] kv = parte.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String clave = kv[0].trim();
            String valor = kv[1].trim();
            if ("ts".equals(clave)) {
                ts = valor;
            } else if ("v1".equals(clave)) {
                v1 = valor.toLowerCase(Locale.ROOT);
            }
        }
        if (ts == null || ts.isBlank() || v1 == null || v1.isBlank()) {
            return false;
        }
        String esperada = calcularHmac(secreto.trim(), construirManifiesto(dataId, xRequestId, ts));
        return MessageDigest.isEqual(
                esperada.getBytes(StandardCharsets.UTF_8),
                v1.getBytes(StandardCharsets.UTF_8));
    }

    /** Calcula la firma HMAC-SHA256 en formato hexadecimal minuscula para pruebas y diagnostico. */
    public static String firmar(String secreto, String dataId, String xRequestId, String ts) {
        return calcularHmac(secreto, construirManifiesto(dataId, xRequestId, ts));
    }

    static String construirManifiesto(String dataId, String xRequestId, String ts) {
        StringBuilder sb = new StringBuilder();
        if (dataId != null && !dataId.isBlank()) {
            sb.append("id:").append(dataId.trim().toLowerCase(Locale.ROOT)).append(';');
        }
        if (xRequestId != null && !xRequestId.isBlank()) {
            sb.append("request-id:").append(xRequestId.trim()).append(';');
        }
        if (ts != null && !ts.isBlank()) {
            sb.append("ts:").append(ts.trim()).append(';');
        }
        return sb.toString();
    }

    private static String calcularHmac(String secreto, String manifiesto) {
        try {
            Mac mac = Mac.getInstance(ALGORITMO);
            mac.init(new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), ALGORITMO));
            return HexFormat.of().formatHex(mac.doFinal(manifiesto.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo calcular HMAC-SHA256", e);
        }
    }
}

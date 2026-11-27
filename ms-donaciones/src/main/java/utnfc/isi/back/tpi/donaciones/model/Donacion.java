package utnfc.isi.back.tpi.donaciones.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import utnfc.isi.back.tpi.common.exception.ConflictoException;
import utnfc.isi.back.tpi.common.exception.SolicitudInvalidaException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Donacion a la cuenta comun de la organizacion.
 *
 * - referencia: identificador propio y unico de la donacion que se envia a Mercado Pago como
 *   external_reference; permite encontrar el pago sin webhooks (D-03).
 * - pagoId: identificador del pago aprobado en el proveedor. UNIQUE: un mismo pago no puede
 *   confirmar dos donaciones (D-03).
 */
@Entity
@Table(name = "donacion", uniqueConstraints = {
        @UniqueConstraint(name = "uk_donacion_donante_idempotency_key", columnNames = {"donante_id", "idempotency_key"})
}, indexes = {
        @Index(name = "idx_donacion_donante_id", columnList = "donante_id"),
        @Index(name = "idx_donacion_estado", columnList = "estado")
})
public class Donacion {

    public static final String MONEDA = "ARS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String referencia;

    @Column(name = "donante_id", nullable = false, length = 64)
    private String donanteId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal monto;

    @Column(nullable = false, length = 3)
    private String moneda;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoDonacion estado;

    @Column(name = "fecha_creacion", nullable = false)
    private LocalDateTime fechaCreacion;

    @Column(name = "fecha_actualizacion", nullable = false)
    private LocalDateTime fechaActualizacion;

    @Column(name = "link_pago", nullable = false, length = 1000)
    private String linkPago;

    @Column(name = "preferencia_id", length = 100)
    private String preferenciaId;

    @Column(name = "pago_id", unique = true, length = 64)
    private String pagoId;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Version
    private long version;

    protected Donacion() {
        // requerido por JPA
    }

    public Donacion(String referencia, String donanteId, BigDecimal monto, String preferenciaId, String linkPago,
                    LocalDateTime ahora) {
        this(referencia, donanteId, monto, preferenciaId, linkPago, ahora, null, null);
    }

    public Donacion(String referencia, String donanteId, BigDecimal monto, String preferenciaId, String linkPago,
                    LocalDateTime ahora, String idempotencyKey, String requestHash) {
        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            throw new SolicitudInvalidaException("El monto de una donacion debe ser mayor a 0 (D-01)");
        }
        this.referencia = referencia;
        this.donanteId = donanteId;
        this.monto = monto;
        this.moneda = MONEDA;
        this.estado = EstadoDonacion.PENDIENTE;
        this.preferenciaId = preferenciaId;
        this.linkPago = linkPago;
        this.fechaCreacion = ahora;
        this.fechaActualizacion = ahora;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
    }

    /** D-01: el monto debe cubrir el minimo configurado en la politica de donaciones. */
    public static void validarMonto(BigDecimal monto, BigDecimal montoMinimo) {
        if (monto == null || monto.compareTo(montoMinimo) < 0) {
            throw new SolicitudInvalidaException("El monto minimo de una donacion es " + montoMinimo + " ARS (D-01)");
        }
    }

    /** D-02: solo pasa a CONFIRMADA con un pago aprobado. D-03: CONFIRMADA no vuelve a cambiar. */
    public void confirmar(String pagoId, LocalDateTime ahora) {
        if (pagoId == null || pagoId.isBlank()) {
            throw new SolicitudInvalidaException("El identificador del pago es obligatorio (D-02)");
        }
        if (estado == EstadoDonacion.CONFIRMADA) {
            throw new ConflictoException("La donacion " + id + " ya esta confirmada (D-03)");
        }
        this.pagoId = pagoId.trim();
        this.estado = EstadoDonacion.CONFIRMADA;
        this.fechaActualizacion = ahora;
    }

    public boolean perteneceA(String usuario) {
        return donanteId.equals(usuario);
    }

    public Long getId() {
        return id;
    }

    public String getReferencia() {
        return referencia;
    }

    public String getDonanteId() {
        return donanteId;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public String getMoneda() {
        return moneda;
    }

    public EstadoDonacion getEstado() {
        return estado;
    }

    public LocalDateTime getFechaCreacion() {
        return fechaCreacion;
    }

    public LocalDateTime getFechaActualizacion() {
        return fechaActualizacion;
    }

    public String getLinkPago() {
        return linkPago;
    }

    public String getPreferenciaId() {
        return preferenciaId;
    }

    public String getPagoId() {
        return pagoId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }
}

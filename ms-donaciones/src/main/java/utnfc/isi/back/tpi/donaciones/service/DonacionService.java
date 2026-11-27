package utnfc.isi.back.tpi.donaciones.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import utnfc.isi.back.tpi.common.exception.AccesoDenegadoException;
import utnfc.isi.back.tpi.common.exception.ConflictoException;
import utnfc.isi.back.tpi.common.exception.RecursoNoEncontradoException;
import utnfc.isi.back.tpi.common.exception.ServicioNoDisponibleException;
import utnfc.isi.back.tpi.donaciones.client.MercadoPagoClient;
import utnfc.isi.back.tpi.donaciones.config.DonacionesProperties;
import utnfc.isi.back.tpi.donaciones.dto.DonacionDtos.DonacionResponse;
import utnfc.isi.back.tpi.donaciones.dto.DonacionDtos.TotalResponse;
import utnfc.isi.back.tpi.donaciones.model.Donacion;
import utnfc.isi.back.tpi.donaciones.model.EstadoDonacion;
import utnfc.isi.back.tpi.donaciones.repository.DonacionRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Proceso P5: recepcion de donaciones (reglas D-01 a D-04).
 * Las llamadas a Mercado Pago se hacen fuera de las transacciones de base de datos.
 */
@Service
public class DonacionService {

    private static final Logger log = LoggerFactory.getLogger(DonacionService.class);

    private final DonacionRepository repository;
    private final MercadoPagoClient mercadoPago;
    private final DonacionesProperties props;
    private final TransactionTemplate tx;
    private final TransactionTemplate txReadOnly;
    private final Clock clock;

    public DonacionService(DonacionRepository repository, MercadoPagoClient mercadoPago, DonacionesProperties props,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.mercadoPago = mercadoPago;
        this.props = props;
        this.tx = new TransactionTemplate(transactionManager);
        this.txReadOnly = new TransactionTemplate(transactionManager);
        this.txReadOnly.setReadOnly(true);
        this.clock = clock;
    }

    /**
     * D-01 y creacion del cobro. Si Mercado Pago falla, la excepcion (503) se propaga ANTES de
     * guardar: no queda ninguna donacion registrada sin enlace de pago.
     */
    public DonacionResponse crear(String donanteId, BigDecimal monto) {
        return crear(donanteId, monto, null);
    }

    /**
     * Crea una donacion con soporte opcional de {@code Idempotency-Key} acotada por donante (ADR-18):
     * misma clave + mismo monto devuelve la donacion existente sin cobrar dos veces; misma clave +
     * monto distinto responde 409 Conflict.
     */
    public DonacionResponse crear(String donanteId, BigDecimal monto, String idempotencyKey) {
        Donacion.validarMonto(monto, props.montoMinimo());
        String clave = (idempotencyKey == null || idempotencyKey.isBlank()) ? null : idempotencyKey.trim();
        String hash = clave == null ? null : hashSolicitud(monto);

        if (clave != null) {
            Optional<Donacion> existente = txReadOnly.execute(
                    st -> repository.findByDonanteIdAndIdempotencyKey(donanteId, clave));
            if (existente.isPresent()) {
                return resolverIdempotencia(existente.get(), hash);
            }
        }

        String referencia = UUID.randomUUID().toString();
        MercadoPagoClient.Cobro cobro = mercadoPago.crearCobro(referencia, monto, props.mercadoPago().tituloItem());
        Donacion donacion = new Donacion(referencia, donanteId, monto, cobro.preferenciaId(), cobro.linkPago(),
                LocalDateTime.now(clock), clave, hash);
        try {
            DonacionResponse respuesta = tx.execute(st -> DonacionResponse.desde(repository.saveAndFlush(donacion), true));
            log.info("Donacion {} creada PENDIENTE por {} ARS", respuesta.id(), monto);
            return respuesta;
        } catch (DataIntegrityViolationException e) {
            if (clave != null) {
                Optional<Donacion> ganadora = txReadOnly.execute(
                        st -> repository.findByDonanteIdAndIdempotencyKey(donanteId, clave));
                if (ganadora.isPresent()) {
                    return resolverIdempotencia(ganadora.get(), hash);
                }
            }
            throw e;
        }
    }

    private DonacionResponse resolverIdempotencia(Donacion existente, String hashActual) {
        if (!hashActual.equals(existente.getRequestHash())) {
            throw new ConflictoException("La clave de idempotencia ya fue utilizada con un monto diferente");
        }
        log.info("Reutilizando donacion {} por Idempotency-Key sin duplicar cobro", existente.getId());
        return DonacionResponse.desde(existente, existente.getEstado() == EstadoDonacion.CONFIRMADA);
    }

    private static String hashSolicitud(BigDecimal monto) {
        String canonico = monto.stripTrailingZeros().toPlainString() + "|" + Donacion.MONEDA;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonico.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en la JVM", e);
        }
    }

    /** D-04: el donante ve las suyas; el Coordinador, todas. No consulta al proveedor. */
    public List<DonacionResponse> listar(String usuario, boolean coordinador) {
        return txReadOnly.execute(st -> (coordinador ? repository.findAllByOrderByIdDesc()
                : repository.findByDonanteIdOrderByIdDesc(usuario))
                .stream()
                .map(d -> DonacionResponse.desde(d, d.getEstado() == EstadoDonacion.CONFIRMADA))
                .toList());
    }

    /** D-04: version paginada del listado (LIMIT/OFFSET en SQL). */
    public Page<DonacionResponse> listar(String usuario, boolean coordinador, Pageable pageable) {
        return txReadOnly.execute(st -> (coordinador ? repository.findAll(pageable)
                : repository.findByDonanteId(usuario, pageable))
                .map(d -> DonacionResponse.desde(d, d.getEstado() == EstadoDonacion.CONFIRMADA)));
    }

    /**
     * D-04 y D-02: si la donacion esta PENDIENTE, pregunta a Mercado Pago si hay un pago aprobado
     * con su referencia. Si el proveedor no responde, devuelve la donacion PENDIENTE con
     * verificado = false (se puede volver a consultar mas tarde).
     */
    public DonacionResponse obtener(String usuario, boolean coordinador, Long id) {
        Donacion donacion = txReadOnly.execute(st -> buscar(id));
        if (!coordinador && !donacion.perteneceA(usuario)) {
            throw new AccesoDenegadoException("La donacion " + id + " pertenece a otro donante (D-04)");
        }
        if (donacion.getEstado() == EstadoDonacion.CONFIRMADA) {
            return DonacionResponse.desde(donacion, true);
        }

        Optional<String> pagoAprobado;
        try {
            pagoAprobado = mercadoPago.buscarPagoAprobado(donacion.getReferencia());
        } catch (ServicioNoDisponibleException e) {
            log.warn("No se pudo verificar la donacion {}: queda PENDIENTE", id);
            return DonacionResponse.desde(donacion, false);
        }
        if (pagoAprobado.isEmpty()) {
            return DonacionResponse.desde(donacion, true);
        }
        return confirmar(id, pagoAprobado.get());
    }

    /** D-03: CONFIRMADA es final y un pago (pago_id UNIQUE) no puede confirmar dos donaciones. */
    private DonacionResponse confirmar(Long id, String pagoId) {
        try {
            return tx.execute(st -> {
                Donacion d = buscar(id);
                if (d.getEstado() == EstadoDonacion.PENDIENTE) {
                    d.confirmar(pagoId, LocalDateTime.now(clock));
                    repository.saveAndFlush(d);
                    log.info("Donacion {} CONFIRMADA con el pago {}", id, pagoId);
                }
                return DonacionResponse.desde(d, true);
            });
        } catch (DataIntegrityViolationException e) {
            log.error("El pago {} ya estaba asociado a otra donacion; la donacion {} sigue PENDIENTE", pagoId, id);
            return tx.execute(st -> DonacionResponse.desde(buscar(id), false));
        }
    }

    public TotalResponse total() {
        return txReadOnly.execute(st -> new TotalResponse(
                repository.sumarMontoPorEstado(EstadoDonacion.CONFIRMADA),
                repository.countByEstado(EstadoDonacion.CONFIRMADA)));
    }

    private Donacion buscar(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe la donacion " + id));
    }
}

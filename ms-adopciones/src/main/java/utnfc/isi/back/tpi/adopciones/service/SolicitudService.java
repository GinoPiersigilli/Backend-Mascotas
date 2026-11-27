package utnfc.isi.back.tpi.adopciones.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import utnfc.isi.back.tpi.adopciones.client.AnimalDto;
import utnfc.isi.back.tpi.adopciones.client.AnimalesClient;
import utnfc.isi.back.tpi.adopciones.client.EstadoAnimal;
import utnfc.isi.back.tpi.adopciones.config.PoliticaAdopcion;
import utnfc.isi.back.tpi.adopciones.dto.EventoResponse;
import utnfc.isi.back.tpi.adopciones.dto.SolicitudResponse;
import utnfc.isi.back.tpi.adopciones.model.EstadoSolicitud;
import utnfc.isi.back.tpi.adopciones.model.Perfil;
import utnfc.isi.back.tpi.adopciones.model.ResultadoControl;
import utnfc.isi.back.tpi.adopciones.model.Solicitud;
import utnfc.isi.back.tpi.adopciones.repository.PerfilRepository;
import utnfc.isi.back.tpi.adopciones.repository.SolicitudRepository;
import utnfc.isi.back.tpi.common.exception.AccesoDenegadoException;
import utnfc.isi.back.tpi.common.exception.ConflictoException;
import utnfc.isi.back.tpi.common.exception.RecursoNoEncontradoException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Proceso de adopcion (P2, P3 y P4). Orquesta las reglas del agregado {@link Solicitud},
 * la evaluacion automatica y la colaboracion con el servicio de Animales.
 *
 * Consistencia entre servicios (no hay transaccion distribuida):
 * - Al presentar una solicitud, las llamadas remotas (Animales y Georef) se hacen ANTES de abrir
 *   la transaccion, para no retener una conexion a la base mientras se espera la red.
 * - En las transiciones que cambian el estado del animal, primero se aplica y se envia (flush)
 *   el cambio local dentro de la transaccion, y despues se pide el cambio a Animales. Si Animales
 *   rechaza (409) o no responde (503), la excepcion deshace el cambio local: la solicitud nunca
 *   queda APROBADA sin el animal RESERVADO ("ante la duda no se avanza", dominio 10.6).
 *   El flush previo hace que un conflicto local (version, restriccion) aparezca antes de tocar
 *   Animales. Queda un caso residual documentado: que Animales confirme y luego falle el commit.
 */
@Service
public class SolicitudService {

    private static final Logger log = LoggerFactory.getLogger(SolicitudService.class);

    private final SolicitudRepository solicitudRepository;
    private final PerfilRepository perfilRepository;
    private final AnimalesClient animalesClient;
    private final EvaluadorSolicitud evaluador;
    private final PoliticaAdopcion politica;
    private final TransactionTemplate tx;
    private final TransactionTemplate txReadOnly;
    private final Clock clock;
    private final Counter solicitudesCreadas;
    private final Counter solicitudesAprobadas;

    public SolicitudService(SolicitudRepository solicitudRepository, PerfilRepository perfilRepository,
                            AnimalesClient animalesClient, EvaluadorSolicitud evaluador, PoliticaAdopcion politica,
                            PlatformTransactionManager transactionManager, Clock clock, MeterRegistry meterRegistry) {
        this.solicitudRepository = solicitudRepository;
        this.perfilRepository = perfilRepository;
        this.animalesClient = animalesClient;
        this.evaluador = evaluador;
        this.politica = politica;
        this.tx = new TransactionTemplate(transactionManager);
        this.txReadOnly = new TransactionTemplate(transactionManager);
        this.txReadOnly.setReadOnly(true);
        this.clock = clock;
        this.solicitudesCreadas = Counter.builder("adopciones.solicitudes.creadas")
                .description("Total de solicitudes de adopcion creadas")
                .register(meterRegistry);
        this.solicitudesAprobadas = Counter.builder("adopciones.solicitudes.aprobadas")
                .description("Total de solicitudes de adopcion aprobadas")
                .register(meterRegistry);
    }

    // ------------------------------------------------------------------ P2: presentar

    /**
     * Presenta una solicitud evaluando, en este orden: R-03, R-02, R-01 (con Animales),
     * R-04 (si falla queda RECHAZADA), R-05 y R-06 (con Georef). Resultado: PENDIENTE con marcas.
     */
    public SolicitudResponse crear(Usuario usuario, Long animalId) {
        Perfil perfil = perfilRepository.findById(usuario.id())
                .orElseThrow(() -> new ConflictoException(
                        "Antes de solicitar una adopcion hay que completar el perfil (R-03)"));

        if (solicitudRepository.existsByAdoptanteIdAndEstadoIn(usuario.id(), EstadoSolicitud.activas())) {
            throw new ConflictoException("Ya tiene una solicitud activa; solo se admite una a la vez (R-02)");
        }

        // Colaboracion con Animales: estado y aptitudes. Si no responde -> 503 (R-01 es bloqueante)
        AnimalDto animal = animalesClient.obtener(animalId);
        if (animal.estado() != EstadoAnimal.DISPONIBLE) {
            throw new ConflictoException("El animal " + animalId + " no esta disponible (estado " + animal.estado() + ") (R-01)");
        }
        if (solicitudRepository.existsByAnimalIdAndEstado(animalId, EstadoSolicitud.PENDIENTE)) {
            throw new ConflictoException("El animal " + animalId + " ya tiene una solicitud pendiente de evaluacion (R-01)");
        }

        LocalDateTime ahora = LocalDateTime.now(clock);
        Optional<String> incompatibilidad = evaluador.incompatibilidadDura(animal, perfil);
        Solicitud solicitud;
        if (incompatibilidad.isPresent()) {
            solicitud = Solicitud.rechazadaPorIncompatibilidad(animalId, animal.nombre(), usuario.id(), ahora,
                    incompatibilidad.get());
        } else {
            List<String> observaciones = new ArrayList<>(evaluador.observacionesDeCompatibilidad(animal, perfil));
            EvaluadorSolicitud.ResultadoDistancia distancia = evaluador.evaluarDistancia(perfil);
            if (distancia.observacion() != null) {
                observaciones.add(distancia.observacion());
            }
            solicitud = Solicitud.pendiente(animalId, animal.nombre(), usuario.id(), ahora, distancia.distanciaKm(),
                    distancia.requiereExcepcion(), distancia.noVerificada(), observaciones);
        }

        try {
            SolicitudResponse respuesta = tx.execute(st -> SolicitudResponse.desde(solicitudRepository.saveAndFlush(solicitud)));
            solicitudesCreadas.increment();
            log.info("Solicitud {} del animal {} creada en estado {}", respuesta.id(), animalId, respuesta.estado());
            return respuesta;
        } catch (DataIntegrityViolationException e) {
            // Dos peticiones simultaneas pasaron las verificaciones previas: la restriccion UNIQUE decide
            throw new ConflictoException("Otra solicitud sobre el mismo animal o del mismo adoptante se registro "
                    + "al mismo tiempo (R-01/R-02)");
        }
    }

    // ------------------------------------------------------------------ consultas

    public List<SolicitudResponse> listar(Usuario usuario, EstadoSolicitud estado) {
        return txReadOnly.execute(st -> {
            List<Solicitud> solicitudes;
            if (usuario.coordinador()) {
                solicitudes = estado == null ? solicitudRepository.findAllByOrderByIdDesc()
                        : solicitudRepository.findByEstadoOrderByIdDesc(estado);
            } else {
                solicitudes = estado == null ? solicitudRepository.findByAdoptanteIdOrderByIdDesc(usuario.id())
                        : solicitudRepository.findByAdoptanteIdAndEstadoOrderByIdDesc(usuario.id(), estado);
            }
            return solicitudes.stream().map(SolicitudResponse::desde).toList();
        });
    }

    public Page<SolicitudResponse> listar(Usuario usuario, EstadoSolicitud estado, Pageable pageable) {
        return txReadOnly.execute(st -> {
            Page<Solicitud> pagina;
            if (usuario.coordinador()) {
                pagina = estado == null ? solicitudRepository.findAll(pageable)
                        : solicitudRepository.findByEstado(estado, pageable);
            } else {
                pagina = estado == null ? solicitudRepository.findByAdoptanteId(usuario.id(), pageable)
                        : solicitudRepository.findByAdoptanteIdAndEstado(usuario.id(), estado, pageable);
            }
            return pagina.map(SolicitudResponse::desde);
        });
    }

    public SolicitudResponse obtener(Usuario usuario, Long id) {
        return txReadOnly.execute(st -> SolicitudResponse.desde(buscarVisible(usuario, id)));
    }

    public List<EventoResponse> historial(Usuario usuario, Long id) {
        return txReadOnly.execute(st -> buscarVisible(usuario, id).getEventos().stream().map(EventoResponse::desde).toList());
    }

    // ------------------------------------------------------------------ P2: decisiones

    public SolicitudResponse cancelar(Usuario usuario, Long id) {
        return soloLocal(id, s -> {
            exigirDuenio(usuario, s);
            s.cancelarPorAdoptante(LocalDateTime.now(clock), usuario.id());
        });
    }

    /** R-07: aprueba y pide a Animales reservar al animal. Si Animales no confirma, no se aprueba. */
    public SolicitudResponse aprobar(Usuario usuario, Long id, String justificacion) {
        SolicitudResponse respuesta = conCambioDeAnimal(id, EstadoAnimal.RESERVADO,
                s -> s.aprobar(justificacion, LocalDateTime.now(clock), usuario.id()));
        solicitudesAprobadas.increment();
        return respuesta;
    }

    /** R-08: el rechazo no toca al animal, que sigue DISPONIBLE. */
    public SolicitudResponse rechazar(Usuario usuario, Long id, String motivo) {
        return soloLocal(id, s -> s.rechazar(motivo, LocalDateTime.now(clock), usuario.id()));
    }

    // ------------------------------------------------------------------ P3: entrega

    /** R-09 y R-10. Si el plazo ya vencio, se aplica el vencimiento y se informa 409. */
    public SolicitudResponse confirmarEntrega(Usuario usuario, Long id) {
        Boolean vencida = txReadOnly.execute(st -> buscar(id).plazoEntregaVencido(LocalDateTime.now(clock), politica.plazoEntregaDias()));
        if (Boolean.TRUE.equals(vencida)) {
            vencerEntrega(id);
            throw new ConflictoException("El plazo de entrega de " + politica.plazoEntregaDias() + " dias vencio: "
                    + "la solicitud " + id + " se cancelo y el animal volvio a estar disponible (R-09)");
        }
        return conCambioDeAnimal(id, EstadoAnimal.EN_ADAPTACION,
                s -> s.confirmarEntrega(LocalDateTime.now(clock), politica.plazoEntregaDias(),
                        politica.periodoAdaptacionDias(), usuario.id()));
    }

    public SolicitudResponse cancelarEntrega(Usuario usuario, Long id, String motivo) {
        return conCambioDeAnimal(id, EstadoAnimal.DISPONIBLE,
                s -> s.cancelarEntrega(motivo, LocalDateTime.now(clock), usuario.id()));
    }

    /**
     * R-09 para la tarea programada: si la solicitud sigue APROBADA y su plazo vencio, la cancela
     * y libera al animal. Devuelve false si ya no correspondia (otro la resolvio antes).
     * Si Animales no responde, la excepcion deshace todo y se reintenta en la proxima ejecucion.
     */
    public boolean vencerEntrega(Long id) {
        animalesClient.asegurarToken();
        Boolean resultado = tx.execute(st -> {
            Solicitud s = buscar(id);
            LocalDateTime ahora = LocalDateTime.now(clock);
            if (!s.plazoEntregaVencido(ahora, politica.plazoEntregaDias())) {
                return false;
            }
            s.vencerPlazoEntrega(ahora, politica.plazoEntregaDias());
            solicitudRepository.saveAndFlush(s);
            animalesClient.cambiarEstado(s.getAnimalId(), EstadoAnimal.DISPONIBLE, id);
            log.info("Solicitud {} cancelada por vencimiento del plazo de entrega; animal {} liberado", id, s.getAnimalId());
            return true;
        });
        return Boolean.TRUE.equals(resultado);
    }

    /** Ids de solicitudes APROBADAS cuya fecha de aprobacion es anterior al plazo (R-09). */
    public List<Long> buscarEntregasVencidas() {
        LocalDateTime limite = LocalDateTime.now(clock).minusDays(politica.plazoEntregaDias());
        return txReadOnly.execute(st -> solicitudRepository
                .findByEstadoAndFechaAprobacionBefore(EstadoSolicitud.APROBADA, limite)
                .stream().map(Solicitud::getId).toList());
    }

    // ------------------------------------------------------------------ P4: seguimiento

    /** R-11 (CONFORME -> animal ADOPTADO) y R-12 (DESFAVORABLE -> animal DISPONIBLE). */
    public SolicitudResponse registrarControl(Usuario usuario, Long id, ResultadoControl resultado, String observaciones) {
        EstadoAnimal destino = resultado == ResultadoControl.CONFORME ? EstadoAnimal.ADOPTADO : EstadoAnimal.DISPONIBLE;
        return conCambioDeAnimal(id, destino,
                s -> s.registrarControl(resultado, observaciones, LocalDateTime.now(clock), usuario.id()));
    }

    /** R-12: devolucion pedida por el adoptante durante la adaptacion. */
    public SolicitudResponse devolver(Usuario usuario, Long id, String motivo) {
        return conCambioDeAnimal(id, EstadoAnimal.DISPONIBLE, s -> {
            exigirDuenio(usuario, s);
            s.devolver(motivo, LocalDateTime.now(clock), usuario.id());
        });
    }

    // ------------------------------------------------------------------ auxiliares

    private SolicitudResponse soloLocal(Long id, Consumer<Solicitud> cambio) {
        return tx.execute(st -> {
            Solicitud s = buscar(id);
            cambio.accept(s);
            return SolicitudResponse.desde(solicitudRepository.saveAndFlush(s));
        });
    }

    /** Aplica el cambio local, lo envia a la base y recien entonces pide el cambio a Animales. */
    private SolicitudResponse conCambioDeAnimal(Long id, EstadoAnimal destinoAnimal, Consumer<Solicitud> cambio) {
        // Precalienta el token de servicio fuera de la transaccion para evitar un POST a Keycloak con la conexion abierta (ADR-15)
        animalesClient.asegurarToken();
        return tx.execute(st -> {
            Solicitud s = buscar(id);
            cambio.accept(s);
            Solicitud guardada = solicitudRepository.saveAndFlush(s);
            animalesClient.cambiarEstado(guardada.getAnimalId(), destinoAnimal, id);
            log.info("Solicitud {} -> {}; animal {} -> {}", id, guardada.getEstado(), guardada.getAnimalId(), destinoAnimal);
            return SolicitudResponse.desde(guardada);
        });
    }

    private Solicitud buscar(Long id) {
        return solicitudRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe la solicitud " + id));
    }

    /** El Coordinador ve todas; el adoptante solo las suyas (403 si es ajena). */
    private Solicitud buscarVisible(Usuario usuario, Long id) {
        Solicitud s = buscar(id);
        if (!usuario.coordinador() && !s.perteneceA(usuario.id())) {
            throw new AccesoDenegadoException("La solicitud " + id + " pertenece a otro adoptante");
        }
        return s;
    }

    private static void exigirDuenio(Usuario usuario, Solicitud s) {
        if (!s.perteneceA(usuario.id())) {
            throw new AccesoDenegadoException("La solicitud " + s.getId() + " pertenece a otro adoptante");
        }
    }
}

package ar.org.refugio.animales.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ar.org.refugio.animales.dto.AnimalRequest;
import ar.org.refugio.animales.dto.AnimalResponse;
import ar.org.refugio.animales.model.Animal;
import ar.org.refugio.animales.model.EstadoAnimal;
import ar.org.refugio.animales.model.Especie;
import ar.org.refugio.animales.repository.AnimalRepository;
import ar.org.refugio.common.exception.RecursoNoEncontradoException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Casos de uso del catalogo. Las reglas de estado viven en la entidad {@link Animal};
 * el servicio coordina la carga, la transaccion y la traduccion a DTO.
 */
@Service
public class AnimalService {

    private static final Logger log = LoggerFactory.getLogger(AnimalService.class);

    private final AnimalRepository repository;
    private final Clock clock;

    public AnimalService(AnimalRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public AnimalResponse crear(AnimalRequest req) {
        LocalDateTime ahora = LocalDateTime.now(clock);
        Animal animal = new Animal(req.nombre(), req.especie(), req.edadMeses(), req.tamanio(),
                req.necesidadesEspeciales(), req.aptoNinios(), req.aptoOtrosAnimales(), req.requiereEspacioExterior(), ahora);
        Animal guardado = repository.save(animal);
        log.info("Alta del animal {} ({})", guardado.getId(), guardado.getNombre());
        return AnimalResponse.desde(guardado);
    }

    @Transactional(readOnly = true)
    public List<AnimalResponse> listar(EstadoAnimal estado, Especie especie) {
        List<Animal> animales;
        if (estado != null && especie != null) {
            animales = repository.findByEstadoAndEspecieOrderByIdAsc(estado, especie);
        } else if (estado != null) {
            animales = repository.findByEstadoOrderByIdAsc(estado);
        } else if (especie != null) {
            animales = repository.findByEspecieOrderByIdAsc(especie);
        } else {
            animales = repository.findAllByOrderByIdAsc();
        }
        return animales.stream().map(AnimalResponse::desde).toList();
    }

    @Transactional(readOnly = true)
    public Page<AnimalResponse> listar(EstadoAnimal estado, Especie especie, Pageable pageable) {
        Page<Animal> pagina;
        if (estado != null && especie != null) {
            pagina = repository.findByEstadoAndEspecie(estado, especie, pageable);
        } else if (estado != null) {
            pagina = repository.findByEstado(estado, pageable);
        } else if (especie != null) {
            pagina = repository.findByEspecie(especie, pageable);
        } else {
            pagina = repository.findAll(pageable);
        }
        return pagina.map(AnimalResponse::desde);
    }

    @Transactional(readOnly = true)
    public AnimalResponse obtener(Long id) {
        return AnimalResponse.desde(buscar(id));
    }

    @Transactional
    public AnimalResponse actualizar(Long id, AnimalRequest req) {
        Animal animal = buscar(id);
        animal.actualizarDatos(req.nombre(), req.especie(), req.edadMeses(), req.tamanio(),
                req.necesidadesEspeciales(), req.aptoNinios(), req.aptoOtrosAnimales(), req.requiereEspacioExterior(),
                LocalDateTime.now(clock));
        return AnimalResponse.desde(animal);
    }

    @Transactional
    public AnimalResponse marcarNoDisponible(Long id, String motivo) {
        Animal animal = buscar(id);
        animal.marcarNoDisponible(motivo, LocalDateTime.now(clock));
        log.info("Animal {} marcado NO_DISPONIBLE", id);
        return AnimalResponse.desde(animal);
    }

    @Transactional
    public AnimalResponse marcarDisponible(Long id) {
        Animal animal = buscar(id);
        animal.marcarDisponible(LocalDateTime.now(clock));
        log.info("Animal {} vuelve a DISPONIBLE", id);
        return AnimalResponse.desde(animal);
    }

    /**
     * API interna: el servicio de Adopciones pide llevar al animal a otro estado. Si la
     * transicion no es valida la entidad lanza ConflictoException (409) y no se modifica nada.
     * saveAndFlush fuerza el chequeo de version dentro de esta transaccion: si otra operacion
     * cambio el animal al mismo tiempo, se responde 409 y no se confirma un estado pisado.
     */
    @Transactional
    public AnimalResponse cambiarEstadoPorProceso(Long id, EstadoAnimal nuevoEstado) {
        return cambiarEstadoPorProceso(id, nuevoEstado, null);
    }

    @Transactional
    public AnimalResponse cambiarEstadoPorProceso(Long id, EstadoAnimal nuevoEstado, Long solicitudId) {
        Animal animal = buscar(id);
        EstadoAnimal anterior = animal.getEstado();
        animal.cambiarEstadoPorProceso(nuevoEstado, LocalDateTime.now(clock), solicitudId);
        repository.saveAndFlush(animal);
        if (anterior == nuevoEstado) {
            log.info("Animal {}: reintento idempotente en {} (solicitud {})", id, nuevoEstado, solicitudId);
        } else {
            log.info("Animal {}: {} -> {} (pedido por el proceso de adopcion, solicitud {})",
                    id, anterior, nuevoEstado, solicitudId);
        }
        return AnimalResponse.desde(animal);
    }

    private Animal buscar(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el animal " + id));
    }
}

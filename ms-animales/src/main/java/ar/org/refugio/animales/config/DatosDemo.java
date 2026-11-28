package ar.org.refugio.animales.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ar.org.refugio.animales.model.Animal;
import ar.org.refugio.animales.model.Especie;
import ar.org.refugio.animales.model.Tamanio;
import ar.org.refugio.animales.repository.AnimalRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Carga un catalogo inicial SOLO si la tabla esta vacia y la propiedad animales.datos-demo
 * es verdadera. Sirve para que la demostracion no dependa de cargas manuales improvisadas.
 * Cada animal esta pensado para ejercitar una regla de negocio distinta.
 */
@Component
@ConditionalOnProperty(name = "animales.datos-demo", havingValue = "true")
public class DatosDemo implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatosDemo.class);

    private final AnimalRepository repository;
    private final Clock clock;

    public DatosDemo(AnimalRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            return;
        }
        LocalDateTime ahora = LocalDateTime.now(clock);
        Animal rocco = new Animal("Rocco", Especie.PERRO, 60, Tamanio.GRANDE, "Recuperacion de una cirugia",
                true, true, false, ahora);
        rocco.marcarNoDisponible("Tratamiento veterinario en curso", ahora);
        repository.saveAll(List.of(
                // Compatible con casi cualquier hogar: camino feliz
                new Animal("Luna", Especie.PERRO, 24, Tamanio.MEDIANO, null, true, true, false, ahora),
                // Necesita patio (R-05: observacion) y no convive con otros animales (R-04)
                new Animal("Toby", Especie.PERRO, 36, Tamanio.GRANDE, null, true, false, true, ahora),
                // No apto con ninios (R-04: rechazo automatico si el hogar tiene ninios)
                new Animal("Michi", Especie.GATO, 12, Tamanio.PEQUENIO, "Asustadizo", false, true, false, ahora),
                // NO_DISPONIBLE: no puede recibir solicitudes (R-01)
                rocco));
        log.info("Se cargaron 4 animales de demostracion");
    }
}

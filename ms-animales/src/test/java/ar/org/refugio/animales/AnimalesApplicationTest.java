package ar.org.refugio.animales;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ar.org.refugio.animales.model.EstadoAnimal;
import ar.org.refugio.animales.repository.AnimalRepository;

import static org.assertj.core.api.Assertions.assertThat;

/** El contexto completo arranca (seguridad, JPA, OpenAPI, JAR comun) y carga los datos de demostracion. */
@SpringBootTest(properties = "animales.datos-demo=true")
@ActiveProfiles("test")
@Tag("integration")
class AnimalesApplicationTest {

    @Autowired
    private AnimalRepository repository;

    @Test
    void arrancaYCargaElCatalogoDeDemostracion() {
        assertThat(repository.count()).isEqualTo(4);
        assertThat(repository.findByEstadoOrderByIdAsc(EstadoAnimal.NO_DISPONIBLE)).hasSize(1);
    }
}

package ar.org.refugio.animales.repository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import ar.org.refugio.animales.model.Animal;
import ar.org.refugio.animales.model.EstadoAnimal;
import ar.org.refugio.animales.model.Especie;
import ar.org.refugio.animales.model.Tamanio;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Tag("integration")
class AnimalRepositoryTest {

    @Autowired
    private AnimalRepository repository;

    @Autowired
    private TestEntityManager em;

    @Test
    void persisteYFiltraPorEstadoYEspecie() {
        Animal luna = repository.save(new Animal("Luna", Especie.PERRO, 24, Tamanio.MEDIANO, null, true, true, false));
        Animal michi = new Animal("Michi", Especie.GATO, 12, Tamanio.PEQUENIO, null, false, true, false);
        michi.marcarNoDisponible("Cuarentena");
        repository.save(michi);
        em.flush();
        em.clear();

        assertThat(repository.findByEstadoOrderByIdAsc(EstadoAnimal.DISPONIBLE))
                .extracting(Animal::getNombre).containsExactly("Luna");
        assertThat(repository.findByEspecieOrderByIdAsc(Especie.GATO))
                .extracting(Animal::getNombre).containsExactly("Michi");
        assertThat(repository.findByEstadoAndEspecieOrderByIdAsc(EstadoAnimal.NO_DISPONIBLE, Especie.PERRO)).isEmpty();
        assertThat(repository.findById(luna.getId())).get()
                .extracting(Animal::getEstado).isEqualTo(EstadoAnimal.DISPONIBLE);
    }

    @Test
    void cadaCambioIncrementaLaVersionParaElBloqueoOptimista() {
        Animal luna = repository.saveAndFlush(new Animal("Luna", Especie.PERRO, 24, Tamanio.MEDIANO, null, true, true, false));
        long versionInicial = luna.getVersion();

        luna.cambiarEstadoPorProceso(EstadoAnimal.RESERVADO);
        repository.saveAndFlush(luna);

        assertThat(luna.getVersion()).isGreaterThan(versionInicial);
    }
}

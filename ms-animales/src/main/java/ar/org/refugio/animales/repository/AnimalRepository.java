package ar.org.refugio.animales.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ar.org.refugio.animales.model.Animal;
import ar.org.refugio.animales.model.EstadoAnimal;
import ar.org.refugio.animales.model.Especie;

import java.util.List;

public interface AnimalRepository extends JpaRepository<Animal, Long> {

    List<Animal> findAllByOrderByIdAsc();

    List<Animal> findByEstadoOrderByIdAsc(EstadoAnimal estado);

    List<Animal> findByEspecieOrderByIdAsc(Especie especie);

    List<Animal> findByEstadoAndEspecieOrderByIdAsc(EstadoAnimal estado, Especie especie);

    Page<Animal> findByEstado(EstadoAnimal estado, Pageable pageable);

    Page<Animal> findByEspecie(Especie especie, Pageable pageable);

    Page<Animal> findByEstadoAndEspecie(EstadoAnimal estado, Especie especie, Pageable pageable);
}

package utnfc.isi.back.tpi.animales.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import utnfc.isi.back.tpi.animales.model.Animal;
import utnfc.isi.back.tpi.animales.model.EstadoAnimal;
import utnfc.isi.back.tpi.animales.model.Especie;

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

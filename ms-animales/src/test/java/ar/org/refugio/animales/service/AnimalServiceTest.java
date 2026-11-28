package ar.org.refugio.animales.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import ar.org.refugio.animales.dto.AnimalRequest;
import ar.org.refugio.animales.dto.AnimalResponse;
import ar.org.refugio.animales.model.Animal;
import ar.org.refugio.animales.model.Especie;
import ar.org.refugio.animales.model.EstadoAnimal;
import ar.org.refugio.animales.model.Tamanio;
import ar.org.refugio.animales.repository.AnimalRepository;
import ar.org.refugio.common.exception.RecursoNoEncontradoException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** Pruebas unitarias de casos de uso y filtros de AnimalService. */
@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AnimalServiceTest {

    private static final Instant AHORA = Instant.parse("2026-06-01T10:00:00Z");

    @Mock
    private AnimalRepository repository;

    private AnimalService service() {
        return new AnimalService(repository, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    private static Animal perro() {
        return new Animal("Luna", Especie.PERRO, 24, Tamanio.MEDIANO, null, true, true, false,
                LocalDateTime.ofInstant(AHORA, ZoneOffset.UTC));
    }

    @Test
    void obtenerYActualizarModificanDatosYLanzan404SiNoExiste() {
        Animal luna = perro();
        when(repository.findById(1L)).thenReturn(Optional.of(luna));
        when(repository.findById(99L)).thenReturn(Optional.empty());

        AnimalService svc = service();
        assertThat(svc.obtener(1L).nombre()).isEqualTo("Luna");

        AnimalResponse actualizado = svc.actualizar(1L,
                new AnimalRequest("Luna Editada", Especie.PERRO, 30, Tamanio.GRANDE, "Dieta especial", true, false, true));
        assertThat(actualizado.nombre()).isEqualTo("Luna Editada");
        assertThat(actualizado.edadMeses()).isEqualTo(30);
        assertThat(actualizado.tamanio()).isEqualTo(Tamanio.GRANDE);

        assertThatThrownBy(() -> svc.obtener(99L))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void listarCubriendoCombinacionesDeFiltrosConYSinPaginacion() {
        Animal luna = perro();
        Pageable pageable = PageRequest.of(0, 10);
        when(repository.findByEstadoAndEspecieOrderByIdAsc(EstadoAnimal.DISPONIBLE, Especie.PERRO)).thenReturn(List.of(luna));
        when(repository.findByEstadoOrderByIdAsc(EstadoAnimal.DISPONIBLE)).thenReturn(List.of(luna));
        when(repository.findByEspecieOrderByIdAsc(Especie.PERRO)).thenReturn(List.of(luna));
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(luna));
        when(repository.findByEstadoAndEspecie(EstadoAnimal.DISPONIBLE, Especie.PERRO, pageable)).thenReturn(new PageImpl<>(List.of(luna)));
        when(repository.findByEstado(EstadoAnimal.DISPONIBLE, pageable)).thenReturn(new PageImpl<>(List.of(luna)));
        when(repository.findByEspecie(Especie.PERRO, pageable)).thenReturn(new PageImpl<>(List.of(luna)));
        when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(luna)));

        AnimalService svc = service();
        assertThat(svc.listar(EstadoAnimal.DISPONIBLE, Especie.PERRO)).hasSize(1);
        assertThat(svc.listar(EstadoAnimal.DISPONIBLE, null)).hasSize(1);
        assertThat(svc.listar(null, Especie.PERRO)).hasSize(1);
        assertThat(svc.listar(null, null)).hasSize(1);

        assertThat(svc.listar(EstadoAnimal.DISPONIBLE, Especie.PERRO, pageable).getContent()).hasSize(1);
        assertThat(svc.listar(EstadoAnimal.DISPONIBLE, null, pageable).getContent()).hasSize(1);
        assertThat(svc.listar(null, Especie.PERRO, pageable).getContent()).hasSize(1);
        assertThat(svc.listar(null, null, pageable).getContent()).hasSize(1);
    }
}

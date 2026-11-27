package utnfc.isi.back.tpi.animales.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import utnfc.isi.back.tpi.animales.dto.AnimalRequest;
import utnfc.isi.back.tpi.animales.dto.AnimalResponse;
import utnfc.isi.back.tpi.animales.model.Animal;
import utnfc.isi.back.tpi.animales.model.Especie;
import utnfc.isi.back.tpi.animales.model.EstadoAnimal;
import utnfc.isi.back.tpi.animales.model.Tamanio;
import utnfc.isi.back.tpi.animales.repository.AnimalRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que {@link AnimalService} registre {@code fechaAlta} y {@code fechaActualizacion}
 * utilizando el {@link Clock} inyectable (consistente con ms-adopciones y ms-donaciones).
 */
@SpringBootTest
@ActiveProfiles("test")
class AnimalServiceClockTest {

    private static final Instant T0 = Instant.parse("2026-08-10T09:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-10T11:30:00Z");
    private static final Instant T2 = Instant.parse("2026-08-11T15:45:00Z");
    private static final Instant T3 = Instant.parse("2026-08-12T18:00:00Z");

    @TestConfiguration
    static class RelojFijoConfig {
        @Bean
        @Primary
        RelojControlable relojControlable() {
            return new RelojControlable(T0);
        }
    }

    static class RelojControlable extends Clock {
        private Instant ahora;

        RelojControlable(Instant inicio) {
            this.ahora = inicio;
        }

        void fijar(Instant nuevo) {
            this.ahora = nuevo;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }

    @Autowired
    private AnimalService service;

    @Autowired
    private AnimalRepository repository;

    @Autowired
    private RelojControlable reloj;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        reloj.fijar(T0);
    }

    @Test
    void usaElClockInyectadoParaFechaAltaYFechaActualizacion() {
        LocalDateTime esperadoT0 = LocalDateTime.ofInstant(T0, ZoneOffset.UTC);
        LocalDateTime esperadoT1 = LocalDateTime.ofInstant(T1, ZoneOffset.UTC);
        LocalDateTime esperadoT2 = LocalDateTime.ofInstant(T2, ZoneOffset.UTC);
        LocalDateTime esperadoT3 = LocalDateTime.ofInstant(T3, ZoneOffset.UTC);

        AnimalRequest alta = new AnimalRequest("Milo", Especie.PERRO, 14, Tamanio.MEDIANO, null, true, true, false);
        AnimalResponse creado = service.crear(alta);

        Animal trasAlta = repository.findById(creado.id()).orElseThrow();
        assertThat(trasAlta.getFechaAlta()).isEqualTo(esperadoT0);
        assertThat(trasAlta.getFechaActualizacion()).isEqualTo(esperadoT0);

        reloj.fijar(T1);
        service.actualizar(creado.id(), new AnimalRequest(
                "Milo Actualizado", Especie.PERRO, 15, Tamanio.MEDIANO, "Ninguna", true, true, true));

        Animal trasActualizar = repository.findById(creado.id()).orElseThrow();
        assertThat(trasActualizar.getFechaAlta()).isEqualTo(esperadoT0);
        assertThat(trasActualizar.getFechaActualizacion()).isEqualTo(esperadoT1);

        reloj.fijar(T2);
        service.marcarNoDisponible(creado.id(), "Control veterinario");

        Animal trasNoDisponible = repository.findById(creado.id()).orElseThrow();
        assertThat(trasNoDisponible.getFechaAlta()).isEqualTo(esperadoT0);
        assertThat(trasNoDisponible.getFechaActualizacion()).isEqualTo(esperadoT2);

        reloj.fijar(T3);
        service.marcarDisponible(creado.id());
        service.cambiarEstadoPorProceso(creado.id(), EstadoAnimal.RESERVADO);

        Animal trasReserva = repository.findById(creado.id()).orElseThrow();
        assertThat(trasReserva.getFechaAlta()).isEqualTo(esperadoT0);
        assertThat(trasReserva.getFechaActualizacion()).isEqualTo(esperadoT3);
    }
}

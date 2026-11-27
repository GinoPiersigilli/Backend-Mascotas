package utnfc.isi.back.tpi.animales;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import utnfc.isi.back.tpi.animales.model.Animal;
import utnfc.isi.back.tpi.animales.model.Especie;
import utnfc.isi.back.tpi.animales.model.Tamanio;
import utnfc.isi.back.tpi.animales.repository.AnimalRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que las migraciones Flyway de ms-animales se apliquen sobre PostgreSQL 17 real
 * y coincidan exactamente con las entidades JPA (ddl-auto: validate).
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
class FlywayMigrationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AnimalRepository animalRepository;

    @Test
    void migracionInicialValidaEntidadesYPermitePersistir() {
        Integer migraciones = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM animales.flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class);
        assertThat(migraciones).isEqualTo(1);

        Animal guardado = animalRepository.saveAndFlush(new Animal(
                "Luna", Especie.PERRO, 24, Tamanio.MEDIANO, null, true, true, false));
        assertThat(guardado.getId()).isNotNull();
        assertThat(animalRepository.findById(guardado.getId())).isPresent();
    }
}

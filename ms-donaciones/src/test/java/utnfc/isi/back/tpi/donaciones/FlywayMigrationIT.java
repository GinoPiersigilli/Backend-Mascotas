package utnfc.isi.back.tpi.donaciones;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import utnfc.isi.back.tpi.donaciones.model.Donacion;
import utnfc.isi.back.tpi.donaciones.repository.DonacionRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica que las migraciones Flyway de ms-donaciones se apliquen sobre PostgreSQL 17 real,
 * coincidan con la entidad JPA (ddl-auto: validate) y hagan cumplir las constraints UNIQUE.
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
    private DonacionRepository donacionRepository;

    @Test
    void migracionInicialValidaEntidadYRestriccionesUnicas() {
        Integer migraciones = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM donaciones.flyway_schema_history WHERE version IN ('1', '2') AND success = true",
                Integer.class);
        assertThat(migraciones).isEqualTo(2);

        LocalDateTime ahora = LocalDateTime.of(2026, 5, 1, 10, 0);
        Donacion primera = new Donacion(
                "ref-it-1", "donante-1", new BigDecimal("500.00"), "pref-1", "https://mp.test/pay",
                ahora, "idem-it-1", "hash-500");
        Donacion guardada = donacionRepository.saveAndFlush(primera);
        assertThat(guardada.getId()).isNotNull();

        Donacion duplicada = new Donacion(
                "ref-it-1", "donante-2", new BigDecimal("100.00"), "pref-2", "https://mp.test/pay2", ahora);
        assertThatThrownBy(() -> donacionRepository.saveAndFlush(duplicada))
                .isInstanceOf(DataIntegrityViolationException.class);

        Donacion mismaClaveIdempotencia = new Donacion(
                "ref-it-2", "donante-1", new BigDecimal("500.00"), "pref-3", "https://mp.test/pay3",
                ahora, "idem-it-1", "hash-500");
        assertThatThrownBy(() -> donacionRepository.saveAndFlush(mismaClaveIdempotencia))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

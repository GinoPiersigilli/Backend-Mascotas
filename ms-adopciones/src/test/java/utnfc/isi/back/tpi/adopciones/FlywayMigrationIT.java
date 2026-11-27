package utnfc.isi.back.tpi.adopciones;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import utnfc.isi.back.tpi.adopciones.model.Perfil;
import utnfc.isi.back.tpi.adopciones.model.Solicitud;
import utnfc.isi.back.tpi.adopciones.model.TipoVivienda;
import utnfc.isi.back.tpi.adopciones.repository.PerfilRepository;
import utnfc.isi.back.tpi.adopciones.repository.SolicitudRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica que las migraciones Flyway de ms-adopciones se apliquen sobre PostgreSQL 17 real,
 * coincidan con las entidades JPA (ddl-auto: validate) y hagan cumplir las constraints UNIQUE.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
@TestPropertySource(properties = "adopciones.tareas.habilitadas=false")
class FlywayMigrationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PerfilRepository perfilRepository;

    @Autowired
    private SolicitudRepository solicitudRepository;

    @Test
    void migracionInicialValidaEntidadesYRestriccionesUnicas() {
        Integer migraciones = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM adopciones.flyway_schema_history WHERE version IN ('1', '2', '3') AND success = true",
                Integer.class);
        assertThat(migraciones).isEqualTo(3);

        Integer tablaShedlock = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'adopciones' AND table_name = 'shedlock'",
                Integer.class);
        assertThat(tablaShedlock).isEqualTo(1);

        LocalDateTime ahora = LocalDateTime.of(2026, 5, 1, 10, 0);
        Perfil perfil = new Perfil("sub-it-1");
        perfil.actualizar("Ana Test", "3515550000", "Cordoba", "Cordoba", "Av Colon 1000",
                TipoVivienda.DEPARTAMENTO, false, false, false, ahora);
        perfilRepository.saveAndFlush(perfil);

        Solicitud primera = Solicitud.pendiente(
                99L, "Toby", "sub-it-1", ahora, 3.5, false, false, List.of("Sin patio"));
        Solicitud guardada = solicitudRepository.saveAndFlush(primera);
        assertThat(guardada.getId()).isNotNull();
        assertThat(guardada.getEventos()).hasSize(1);

        Solicitud duplicada = Solicitud.pendiente(
                99L, "Toby", "sub-it-2", ahora, 2.0, false, false, List.of());
        assertThatThrownBy(() -> solicitudRepository.saveAndFlush(duplicada))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

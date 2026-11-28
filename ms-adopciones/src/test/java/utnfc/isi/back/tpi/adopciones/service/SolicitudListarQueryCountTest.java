package utnfc.isi.back.tpi.adopciones.service;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import utnfc.isi.back.tpi.adopciones.dto.SolicitudResponse;
import utnfc.isi.back.tpi.adopciones.model.Solicitud;
import utnfc.isi.back.tpi.adopciones.repository.SolicitudRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que {@link SolicitudService#listar} no incurra en el problema N+1 al leer
 * {@code Solicitud.observaciones} (carga en lotes con {@code @BatchSize(size = 50)}).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Tag("integration")
class SolicitudListarQueryCountTest {

    @Autowired
    private SolicitudService solicitudService;

    @Autowired
    private SolicitudRepository solicitudRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void limpiar() {
        solicitudRepository.deleteAll();
    }

    @Test
    void listarSolicitudesNoCreceLinealmenteEnQueriesConN() {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        sembrarSolicitudes(5);
        long queriesCon5 = medirQueriesAlListar(stats, 5);

        solicitudRepository.deleteAll();
        sembrarSolicitudes(25);
        long queriesCon25 = medirQueriesAlListar(stats, 25);

        // Con @BatchSize(size = 50): 1 query para solicitud + 1 query en lote para solicitud_observacion.
        // Sin @BatchSize serian 6 queries para N=5 y 26 queries para N=25.
        assertThat(queriesCon5).isEqualTo(2);
        assertThat(queriesCon25).isEqualTo(2);
    }

    private void sembrarSolicitudes(int cantidad) {
        LocalDateTime ahora = LocalDateTime.of(2026, 6, 1, 10, 0);
        for (int i = 1; i <= cantidad; i++) {
            Solicitud s = Solicitud.pendiente(
                    (long) i,
                    "Animal-" + i,
                    "adoptante-" + i,
                    ahora.plusMinutes(i),
                    12.5,
                    false,
                    false,
                    List.of("Sin patio", "Observacion " + i)
            );
            solicitudRepository.save(s);
        }
        solicitudRepository.flush();
    }

    private long medirQueriesAlListar(Statistics stats, int esperadas) {
        stats.clear();
        List<SolicitudResponse> resultado = solicitudService.listar(new Usuario("coordinador-1", true), null);
        assertThat(resultado).hasSize(esperadas);
        assertThat(resultado).allSatisfy(r -> assertThat(r.observaciones()).hasSize(2));
        return stats.getPrepareStatementCount();
    }
}

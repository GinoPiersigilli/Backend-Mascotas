package utnfc.isi.back.tpi.adopciones.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import utnfc.isi.back.tpi.adopciones.config.MetricasProperties;
import utnfc.isi.back.tpi.adopciones.model.EstadoSolicitud;
import utnfc.isi.back.tpi.adopciones.repository.SolicitudRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifica que el gauge de solicitudes por estado comparta una sola consulta GROUP BY
 * dentro del TTL configurado, se refresque al vencer y no rompa el scrape ante un error de BD.
 */
@Tag("unit")
class MetricasSolicitudesTest {

    private static final class RelojMutable extends Clock {
        private Instant ahora;

        RelojMutable(Instant inicio) {
            this.ahora = inicio;
        }

        void adelantar(Duration d) {
            ahora = ahora.plus(d);
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

    @Test
    void reutilizaUnaUnicaConsultaDentroDelTtlYRefrescaAlVencer() {
        SolicitudRepository repo = mock(SolicitudRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RelojMutable reloj = new RelojMutable(Instant.parse("2026-11-28T12:00:00Z"));

        when(repo.contarPorEstado()).thenReturn(List.<Object[]>of(
                new Object[]{EstadoSolicitud.PENDIENTE, 3L},
                new Object[]{EstadoSolicitud.APROBADA, 1L}));

        new MetricasSolicitudes(repo, registry, reloj, new MetricasProperties(Duration.ofSeconds(5)));

        // Lectura de los 7 estados dentro del TTL: una sola consulta a BD
        for (EstadoSolicitud estado : EstadoSolicitud.values()) {
            Gauge gauge = registry.find(MetricasSolicitudes.METRICA_SOLICITUDES_POR_ESTADO)
                    .tag("estado", estado.name()).gauge();
            assertThat(gauge).isNotNull();
            double valor = gauge.value();
            if (estado == EstadoSolicitud.PENDIENTE) {
                assertThat(valor).isEqualTo(3.0);
            } else if (estado == EstadoSolicitud.APROBADA) {
                assertThat(valor).isEqualTo(1.0);
            } else {
                assertThat(valor).isEqualTo(0.0);
            }
        }
        verify(repo, times(1)).contarPorEstado();

        // Al vencer el TTL refresca con los nuevos valores
        when(repo.contarPorEstado()).thenReturn(List.<Object[]>of(
                new Object[]{EstadoSolicitud.FINALIZADA, 2L}));
        reloj.adelantar(Duration.ofSeconds(6));

        assertThat(registry.find(MetricasSolicitudes.METRICA_SOLICITUDES_POR_ESTADO)
                .tag("estado", "FINALIZADA").gauge().value()).isEqualTo(2.0);
        assertThat(registry.find(MetricasSolicitudes.METRICA_SOLICITUDES_POR_ESTADO)
                .tag("estado", "PENDIENTE").gauge().value()).isEqualTo(0.0);
        verify(repo, times(2)).contarPorEstado();
    }

    @Test
    void anteErrorDeBaseDevuelveNaNSinRomperElScrape() {
        SolicitudRepository repo = mock(SolicitudRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Clock reloj = Clock.fixed(Instant.parse("2026-11-28T12:00:00Z"), ZoneOffset.UTC);

        when(repo.contarPorEstado()).thenThrow(new IllegalStateException("BD no disponible"));
        new MetricasSolicitudes(repo, registry, reloj, new MetricasProperties(null));

        Gauge gauge = registry.find(MetricasSolicitudes.METRICA_SOLICITUDES_POR_ESTADO)
                .tag("estado", "PENDIENTE").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isNaN();
    }
}

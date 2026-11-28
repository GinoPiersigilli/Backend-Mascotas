package ar.org.refugio.adopciones.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ar.org.refugio.common.exception.ServicioNoDisponibleException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifica que TareaVencimientos aisle errores por solicitud (un fallo no aborta el lote)
 * y registre las metricas de procesadas y fallidas.
 */
@Tag("unit")
class TareaVencimientosTest {

    @Test
    void unFalloEnUnaSolicitudNoAbortaElLoteYRegistraMetricas() {
        SolicitudService solicitudService = mock(SolicitudService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TareaVencimientos tarea = new TareaVencimientos(solicitudService, registry);

        when(solicitudService.buscarEntregasVencidas()).thenReturn(List.of(10L, 20L, 30L));
        when(solicitudService.vencerEntrega(10L)).thenThrow(new ServicioNoDisponibleException("Animales caido"));
        when(solicitudService.vencerEntrega(20L)).thenReturn(true);
        when(solicitudService.vencerEntrega(30L)).thenReturn(false);

        tarea.vencerEntregas();

        verify(solicitudService).vencerEntrega(10L);
        verify(solicitudService).vencerEntrega(20L);
        verify(solicitudService).vencerEntrega(30L);
        assertThat(registry.counter("adopciones.vencimientos.procesados").count()).isEqualTo(1.0);
        assertThat(registry.counter("adopciones.vencimientos.fallidos").count()).isEqualTo(1.0);
    }
}

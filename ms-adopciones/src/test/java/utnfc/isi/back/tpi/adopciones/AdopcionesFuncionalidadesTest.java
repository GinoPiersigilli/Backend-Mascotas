package utnfc.isi.back.tpi.adopciones;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import utnfc.isi.back.tpi.adopciones.client.AnimalDto;
import utnfc.isi.back.tpi.adopciones.client.AnimalesClient;
import utnfc.isi.back.tpi.adopciones.client.Coordenadas;
import utnfc.isi.back.tpi.adopciones.client.EstadoAnimal;
import utnfc.isi.back.tpi.adopciones.client.GeorefClient;
import utnfc.isi.back.tpi.adopciones.event.AdopcionConfirmadaEvent;
import utnfc.isi.back.tpi.adopciones.event.EntregaConfirmadaEvent;
import utnfc.isi.back.tpi.adopciones.event.SolicitudAprobadaEvent;
import utnfc.isi.back.tpi.adopciones.repository.PerfilRepository;
import utnfc.isi.back.tpi.adopciones.repository.SolicitudRepository;
import utnfc.isi.back.tpi.adopciones.service.SolicitudService;
import utnfc.isi.back.tpi.adopciones.service.TareaControlesVencidos;
import utnfc.isi.back.tpi.common.exception.ServicioNoDisponibleException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integracion para las funcionalidades agregadas en WS-4:
 * - F1: alerta idempotente de control final vencido + filtro ?controlVencido=true (COORDINADOR).
 * - F3: reconciliador ADR-06 (auto-reparacion segura y reporte de conflictos).
 * - F4: reportes operativos por estado y tiempo promedio de resolucion (COORDINADOR).
 * - F5: publicacion de eventos de dominio tras el commit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
@Tag("integration")
class AdopcionesFuncionalidadesTest {

    @TestConfiguration
    static class RelojTestConfig {
        @Bean
        @Primary
        RelojMutable relojMutable() {
            return new RelojMutable(Instant.parse("2026-06-01T10:00:00Z"));
        }
    }

    static class RelojMutable extends Clock {
        private Instant actual;

        RelojMutable(Instant inicial) {
            this.actual = inicial;
        }

        void fijar(Instant nuevo) {
            this.actual = nuevo;
        }

        void avanzar(Duration duracion) {
            this.actual = this.actual.plus(duracion);
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
            return actual;
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private RelojMutable reloj;
    @Autowired
    private SolicitudRepository solicitudRepository;
    @Autowired
    private PerfilRepository perfilRepository;
    @Autowired
    private SolicitudService solicitudService;
    @Autowired
    private ApplicationEvents events;

    @MockitoBean
    private AnimalesClient animalesClient;
    @MockitoBean
    private GeorefClient georefClient;

    private static final String PERFIL_OK = """
            {"nombreCompleto":"Laura Paz","telefono":"3515551111","provincia":"Cordoba","localidad":"Cordoba",
             "direccion":"Av Colon 1000","tipoVivienda":"CASA","tienePatio":true,
             "hayNinios":false,"hayOtrosAnimales":false}""";

    private static RequestPostProcessor adoptante(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority("ROLE_ADOPTANTE"));
    }

    private static RequestPostProcessor coordinador() {
        return jwt().jwt(j -> j.subject("coord-1")).authorities(new SimpleGrantedAuthority("ROLE_COORDINADOR"));
    }

    @BeforeEach
    void setUp() {
        solicitudRepository.deleteAll();
        perfilRepository.deleteAll();
        reloj.fijar(Instant.parse("2026-06-01T10:00:00Z"));
        reset(animalesClient, georefClient);
        when(georefClient.geolocalizar(any(), any(), any()))
                .thenReturn(Optional.of(new Coordenadas(-31.41, -64.19)));
        when(animalesClient.obtener(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return new AnimalDto(id, "Animal-" + id, EstadoAnimal.DISPONIBLE, true, true, false);
        });
        when(animalesClient.cambiarEstado(anyLong(), any(), anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            EstadoAnimal estado = inv.getArgument(1);
            return new AnimalDto(id, "Animal-" + id, estado, true, true, false);
        });
    }

    private long crearSolicitud(String sub, long animalId) throws Exception {
        mvc.perform(put("/api/adoptantes/me/perfil").with(adoptante(sub))
                        .contentType(MediaType.APPLICATION_JSON).content(PERFIL_OK))
                .andExpect(status().isOk());
        String resp = mvc.perform(post("/api/solicitudes").with(adoptante(sub))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"animalId\":" + animalId + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.id")).longValue();
    }

    @Test
    void f1ControlFinalVencidoAlertaUnaSolaVezYFiltraParaCoordinador() throws Exception {
        long id = crearSolicitud("adop-1", 10L);
        mvc.perform(post("/api/solicitudes/{id}/aprobar", id).with(coordinador())).andExpect(status().isOk());
        mvc.perform(post("/api/solicitudes/{id}/confirmar-entrega", id).with(coordinador())).andExpect(status().isOk());

        // Antes de los 30 dias no esta vencido
        mvc.perform(get("/api/solicitudes").param("controlVencido", "true").with(coordinador()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "0"))
                .andExpect(jsonPath("$.length()").value(0));

        // Adoptante no puede usar controlVencido=true -> 403
        mvc.perform(get("/api/solicitudes").param("controlVencido", "true").with(adoptante("adop-1")))
                .andExpect(status().isForbidden());

        // Pasan 31 dias: el control final esta vencido
        reloj.avanzar(Duration.ofDays(31));

        mvc.perform(get("/api/solicitudes").param("controlVencido", "true").with(coordinador()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].estado").value("EN_ADAPTACION"));

        // Ejecutar tarea dos veces: registra UNA sola alerta en historial y mantiene estado EN_ADAPTACION
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TareaControlesVencidos tarea = new TareaControlesVencidos(solicitudService, registry);
        tarea.alertarControlesVencidos();
        tarea.alertarControlesVencidos();

        assertThat(registry.counter("adopciones.controles.vencidos.alertados").count()).isEqualTo(1.0);
        mvc.perform(get("/api/solicitudes/{id}", id).with(coordinador()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_ADAPTACION"));
        mvc.perform(get("/api/solicitudes/{id}/historial", id).with(coordinador()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[3].actor").value("sistema"))
                .andExpect(jsonPath("$[3].estadoNuevo").value("EN_ADAPTACION"));
    }

    @Test
    void f3ReconciliadorReparaResidualAdr06YReportaConflictos() throws Exception {
        long idPendiente = crearSolicitud("adop-1", 20L);
        long idAprobada = crearSolicitud("adop-2", 21L);
        mvc.perform(post("/api/solicitudes/{id}/aprobar", idAprobada).with(coordinador())).andExpect(status().isOk());

        // Adoptante no puede ejecutar reconciliacion -> 403
        mvc.perform(post("/api/solicitudes/reconciliacion").with(adoptante("adop-1")))
                .andExpect(status().isForbidden());

        // Simula residual ADR-06: animal 20 quedo RESERVADO aunque solicitud quedo PENDIENTE;
        // y conflicto no auto-reparable: animal 21 en EN_ADAPTACION mientras solicitud esta APROBADA.
        reset(animalesClient);
        when(animalesClient.obtener(20L))
                .thenReturn(new AnimalDto(20L, "Animal-20", EstadoAnimal.RESERVADO, true, true, false));
        when(animalesClient.obtener(21L))
                .thenReturn(new AnimalDto(21L, "Animal-21", EstadoAnimal.EN_ADAPTACION, true, true, false));
        when(animalesClient.cambiarEstado(20L, EstadoAnimal.DISPONIBLE, idPendiente))
                .thenReturn(new AnimalDto(20L, "Animal-20", EstadoAnimal.DISPONIBLE, true, true, false));

        mvc.perform(post("/api/solicitudes/reconciliacion").with(coordinador()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluadas").value(2))
                .andExpect(jsonPath("$.reparadas").value(1))
                .andExpect(jsonPath("$.conflictos").value(1))
                .andExpect(jsonPath("$.erroresRemotos").value(0))
                .andExpect(jsonPath("$.detalles.length()").value(2));

        verify(animalesClient).cambiarEstado(20L, EstadoAnimal.DISPONIBLE, idPendiente);
        verify(animalesClient, never()).cambiarEstado(eq(21L), any(), anyLong());
    }

    @Test
    void f4ReportesResumenParaCoordinadorConConteosYPromedioResolucion() throws Exception {
        // Adoptante no accede a reportes -> 403
        mvc.perform(get("/api/solicitudes/reportes").with(adoptante("adop-1")))
                .andExpect(status().isForbidden());

        // Solicitud 1: rechazada a las 4 horas
        long id1 = crearSolicitud("adop-1", 30L);
        reloj.avanzar(Duration.ofHours(4));
        mvc.perform(post("/api/solicitudes/{id}/rechazar", id1).with(coordinador())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"No cumple criterio\"}"))
                .andExpect(status().isOk());

        // Solicitud 2: cancelada por adoptante a las 8 horas -> promedio resueltas = (4 + 8) / 2 = 6.0 h
        long id2 = crearSolicitud("adop-2", 31L);
        reloj.avanzar(Duration.ofHours(8));
        mvc.perform(post("/api/solicitudes/{id}/cancelar", id2).with(adoptante("adop-2")))
                .andExpect(status().isOk());

        // Solicitud 3: queda PENDIENTE
        crearSolicitud("adop-3", 32L);

        mvc.perform(get("/api/solicitudes/reportes").with(coordinador()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.porEstado.PENDIENTE").value(1))
                .andExpect(jsonPath("$.porEstado.RECHAZADA").value(1))
                .andExpect(jsonPath("$.porEstado.CANCELADA").value(1))
                .andExpect(jsonPath("$.porEstado.FINALIZADA").value(0))
                .andExpect(jsonPath("$.controlesFinalesVencidos").value(0))
                .andExpect(jsonPath("$.tiempoPromedioResolucionHoras").value(6.0));
    }

    @Test
    void f5EventosDeDominioSePublicanEnTransicionesExitosasYNoEnRollback() throws Exception {
        long id = crearSolicitud("adop-1", 40L);

        // Si Animales falla en aprobar -> rollback, NO se publica SolicitudAprobadaEvent
        when(animalesClient.cambiarEstado(40L, EstadoAnimal.RESERVADO, id))
                .thenThrow(new ServicioNoDisponibleException("Animales caido"));
        mvc.perform(post("/api/solicitudes/{id}/aprobar", id).with(coordinador()))
                .andExpect(status().isServiceUnavailable());
        assertThat(events.stream(SolicitudAprobadaEvent.class).count()).isZero();

        // Recuperado: aprobar -> entregar -> control CONFORME publica los 3 eventos
        when(animalesClient.cambiarEstado(anyLong(), any(), anyLong())).thenAnswer(inv -> {
            Long aid = inv.getArgument(0);
            EstadoAnimal est = inv.getArgument(1);
            return new AnimalDto(aid, "Animal-" + aid, est, true, true, false);
        });
        mvc.perform(post("/api/solicitudes/{id}/aprobar", id).with(coordinador())).andExpect(status().isOk());
        mvc.perform(post("/api/solicitudes/{id}/confirmar-entrega", id).with(coordinador())).andExpect(status().isOk());
        reloj.avanzar(Duration.ofDays(30));
        mvc.perform(post("/api/solicitudes/{id}/control-final", id).with(coordinador())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resultado\":\"CONFORME\",\"observaciones\":\"Excelente adaptacion\"}"))
                .andExpect(status().isOk());

        assertThat(events.stream(SolicitudAprobadaEvent.class).toList())
                .hasSize(1)
                .allMatch(e -> e.solicitudId().equals(id) && e.animalId().equals(40L));
        assertThat(events.stream(EntregaConfirmadaEvent.class).toList())
                .hasSize(1)
                .allMatch(e -> e.solicitudId().equals(id) && e.fechaControl() != null);
        assertThat(events.stream(AdopcionConfirmadaEvent.class).toList())
                .hasSize(1)
                .allMatch(e -> e.solicitudId().equals(id) && e.animalId().equals(40L));
    }
}

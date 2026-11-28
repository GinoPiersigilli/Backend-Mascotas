package utnfc.isi.back.tpi.adopciones;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import utnfc.isi.back.tpi.adopciones.client.AnimalDto;
import utnfc.isi.back.tpi.adopciones.client.AnimalesClient;
import utnfc.isi.back.tpi.adopciones.client.Coordenadas;
import utnfc.isi.back.tpi.adopciones.client.EstadoAnimal;
import utnfc.isi.back.tpi.adopciones.client.GeorefClient;
import utnfc.isi.back.tpi.adopciones.repository.PerfilRepository;
import utnfc.isi.back.tpi.adopciones.repository.SolicitudRepository;
import utnfc.isi.back.tpi.common.exception.ServicioNoDisponibleException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica la proteccion de /actuator/prometheus y el registro de metricas de negocio
 * (adopciones.solicitudes.creadas y adopciones.solicitudes.aprobadas).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
@Tag("integration")
class AdopcionesMetricasTest {

    private static final String PERFIL = """
            {"nombreCompleto":"Ana Perez","telefono":"3515550000","provincia":"Cordoba","localidad":"Cordoba",
             "direccion":"Av Colon 1000","tipoVivienda":"CASA","tienePatio":true,"hayNinios":false,"hayOtrosAnimales":false}
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private SolicitudRepository solicitudRepository;

    @Autowired
    private PerfilRepository perfilRepository;

    @MockitoBean
    private AnimalesClient animales;

    @MockitoBean
    private GeorefClient georef;

    private static RequestPostProcessor con(String sub, String rol) {
        return jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    @BeforeEach
    void setUp() {
        solicitudRepository.deleteAll();
        perfilRepository.deleteAll();
        reset(animales, georef);
        when(animales.obtener(1L)).thenReturn(new AnimalDto(1L, "Luna", EstadoAnimal.DISPONIBLE, true, true, false));
        when(animales.obtener(2L)).thenReturn(new AnimalDto(2L, "Toby", EstadoAnimal.DISPONIBLE, true, true, false));
        when(georef.geolocalizar(any(), any(), any())).thenReturn(Optional.of(new Coordenadas(-31.4096, -64.1966)));
    }

    @Test
    void actuatorPrometheusRequiereRolServicio() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(con("ana", "ADOPTANTE"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(con("coord-1", "COORDINADOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(con("prometheus", "SERVICIO")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("application=\"ms-adopciones\"")));
    }

    @Test
    void registraContadoresDeSolicitudesCreadasYAprobadasYLosExponeEnPrometheus() throws Exception {
        double creadasAntes = meterRegistry.counter("adopciones.solicitudes.creadas").count();
        double aprobadasAntes = meterRegistry.counter("adopciones.solicitudes.aprobadas").count();

        mvc.perform(put("/api/adoptantes/me/perfil").with(con("ana", "ADOPTANTE"))
                        .contentType(MediaType.APPLICATION_JSON).content(PERFIL))
                .andExpect(status().isOk());

        String body1 = mvc.perform(post("/api/solicitudes").with(con("ana", "ADOPTANTE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"animalId\":1}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id1 = ((Number) JsonPath.read(body1, "$.id")).longValue();

        assertThat(meterRegistry.counter("adopciones.solicitudes.creadas").count()).isEqualTo(creadasAntes + 1.0);
        assertThat(meterRegistry.counter("adopciones.solicitudes.aprobadas").count()).isEqualTo(aprobadasAntes);

        mvc.perform(post("/api/solicitudes/" + id1 + "/aprobar").with(con("coord-1", "COORDINADOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(meterRegistry.counter("adopciones.solicitudes.aprobadas").count()).isEqualTo(aprobadasAntes + 1.0);

        // Si Animales falla al aprobar, el contador de aprobadas no debe incrementarse
        mvc.perform(put("/api/adoptantes/me/perfil").with(con("beto", "ADOPTANTE"))
                        .contentType(MediaType.APPLICATION_JSON).content(PERFIL))
                .andExpect(status().isOk());
        String body2 = mvc.perform(post("/api/solicitudes").with(con("beto", "ADOPTANTE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"animalId\":2}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id2 = ((Number) JsonPath.read(body2, "$.id")).longValue();

        when(animales.cambiarEstado(2L, EstadoAnimal.RESERVADO, id2))
                .thenThrow(new ServicioNoDisponibleException("Animales no responde"));
        mvc.perform(post("/api/solicitudes/" + id2 + "/aprobar").with(con("coord-1", "COORDINADOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(meterRegistry.counter("adopciones.solicitudes.creadas").count()).isEqualTo(creadasAntes + 2.0);
        assertThat(meterRegistry.counter("adopciones.solicitudes.aprobadas").count()).isEqualTo(aprobadasAntes + 1.0);

        // El endpoint Prometheus expone ambas metricas de negocio en formato scrape
        mvc.perform(get("/actuator/prometheus").with(con("prometheus", "SERVICIO")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("adopciones_solicitudes_creadas_total")))
                .andExpect(content().string(containsString("adopciones_solicitudes_aprobadas_total")));
    }
}

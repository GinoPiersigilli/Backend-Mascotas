package utnfc.isi.back.tpi.adopciones;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import utnfc.isi.back.tpi.adopciones.client.AnimalDto;
import utnfc.isi.back.tpi.adopciones.client.AnimalesClient;
import utnfc.isi.back.tpi.adopciones.client.Coordenadas;
import utnfc.isi.back.tpi.adopciones.client.EstadoAnimal;
import utnfc.isi.back.tpi.adopciones.client.GeorefClient;
import utnfc.isi.back.tpi.adopciones.model.EstadoSolicitud;
import utnfc.isi.back.tpi.adopciones.model.Solicitud;
import utnfc.isi.back.tpi.adopciones.repository.PerfilRepository;
import utnfc.isi.back.tpi.adopciones.repository.SolicitudRepository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica que dos aprobaciones simultaneas sobre la misma solicitud produzcan exactamente
 * un ganador (200 APROBADA) y un conflicto (409 con ErrorResponse) mediante bloqueo optimista
 * (@Version), invocando una sola vez a ms-animales.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
class SolicitudConcurrenciaTest {

    private static final String PERFIL = """
            {"nombreCompleto":"Ana Perez","telefono":"3515550000","provincia":"Cordoba","localidad":"Cordoba",
             "direccion":"Av Colon 1000","tipoVivienda":"CASA","tienePatio":true,"hayNinios":false,"hayOtrosAnimales":false}
            """;

    @Autowired
    private MockMvc mvc;

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
        when(georef.geolocalizar(any(), any(), any())).thenReturn(Optional.of(new Coordenadas(-31.4096, -64.1966)));
    }

    @Test
    void dosAprobacionesSimultaneasProducenUnGanadorYUn409() throws Exception {
        mvc.perform(put("/api/adoptantes/me/perfil").with(con("ana", "ADOPTANTE"))
                        .contentType(MediaType.APPLICATION_JSON).content(PERFIL))
                .andExpect(status().isOk());

        String body = mvc.perform(post("/api/solicitudes").with(con("ana", "ADOPTANTE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"animalId\":1}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long solicitudId = ((Number) JsonPath.read(body, "$.id")).longValue();

        // Sincroniza ambos hilos justo antes de abrir tx.execute(...) y retiene brevemente al ganador
        // mientras mantiene el flush sin commitear para forzar la colision real de @Version.
        CountDownLatch ambosEnAprobar = new CountDownLatch(2);
        doAnswer(inv -> {
            ambosEnAprobar.countDown();
            ambosEnAprobar.await(5, TimeUnit.SECONDS);
            return null;
        }).when(animales).asegurarToken();

        when(animales.cambiarEstado(1L, EstadoAnimal.RESERVADO, solicitudId)).thenAnswer(inv -> {
            Thread.sleep(250);
            return new AnimalDto(1L, "Luna", EstadoAnimal.RESERVADO, true, true, false);
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> f1 = pool.submit(() -> mvc.perform(post("/api/solicitudes/" + solicitudId + "/aprobar")
                            .with(con("coord-1", "COORDINADOR"))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn());
            Future<MvcResult> f2 = pool.submit(() -> mvc.perform(post("/api/solicitudes/" + solicitudId + "/aprobar")
                            .with(con("coord-2", "COORDINADOR"))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn());

            MvcResult r1 = f1.get(15, TimeUnit.SECONDS);
            MvcResult r2 = f2.get(15, TimeUnit.SECONDS);

            List<Integer> estados = List.of(r1.getResponse().getStatus(), r2.getResponse().getStatus());
            assertThat(estados).containsExactlyInAnyOrder(200, 409);

            MvcResult perdedor = r1.getResponse().getStatus() == 409 ? r1 : r2;
            String errorJson = perdedor.getResponse().getContentAsString();
            assertThat((Integer) JsonPath.read(errorJson, "$.status")).isEqualTo(409);
            assertThat((String) JsonPath.read(errorJson, "$.error")).isEqualTo("Conflicto");
            assertThat((String) JsonPath.read(errorJson, "$.path"))
                    .isEqualTo("/api/solicitudes/" + solicitudId + "/aprobar");
        } finally {
            pool.shutdownNow();
        }

        // ADR-06: el flush previo detecto la concurrencia antes de invocar dos veces a ms-animales
        verify(animales, times(1)).cambiarEstado(1L, EstadoAnimal.RESERVADO, solicitudId);
        Solicitud finalizada = solicitudRepository.findById(solicitudId).orElseThrow();
        assertThat(finalizada.getEstado()).isEqualTo(EstadoSolicitud.APROBADA);
        assertThat(finalizada.getVersion()).isEqualTo(1L);
    }
}

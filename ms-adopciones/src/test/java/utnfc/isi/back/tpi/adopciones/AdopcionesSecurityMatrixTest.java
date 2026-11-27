package utnfc.isi.back.tpi.adopciones;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import utnfc.isi.back.tpi.adopciones.client.AnimalDto;
import utnfc.isi.back.tpi.adopciones.client.AnimalesClient;
import utnfc.isi.back.tpi.adopciones.client.Coordenadas;
import utnfc.isi.back.tpi.adopciones.client.EstadoAnimal;
import utnfc.isi.back.tpi.adopciones.client.GeorefClient;
import utnfc.isi.back.tpi.adopciones.repository.PerfilRepository;
import utnfc.isi.back.tpi.adopciones.repository.SolicitudRepository;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Matriz de seguridad negativa de ms-adopciones (WS-5):
 * - IDOR: un adoptante no puede leer, ver historial, cancelar ni pedir devolucion de solicitudes ajenas.
 * - RBAC negativo: sin token (401), ADOPTANTE sobre rutas de COORDINADOR (403), COORDINADOR sobre
 *   rutas de ADOPTANTE (403), SERVICIO sobre /api/** (403), usuarios sobre /actuator/prometheus (403).
 * - Actuator probes liveness/readiness publicos con show-details: never.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdopcionesSecurityMatrixTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private SolicitudRepository solicitudRepository;
    @Autowired
    private PerfilRepository perfilRepository;

    @MockitoBean
    private AnimalesClient animalesClient;
    @MockitoBean
    private GeorefClient georefClient;

    private static final String PERFIL_OK = """
            {"nombreCompleto":"Laura Paz","telefono":"3515551111","provincia":"Cordoba","localidad":"Cordoba",
             "direccion":"Av Colon 1000","tipoVivienda":"CASA","tienePatio":true,
             "hayNinios":false,"hayOtrosAnimales":false}""";

    private static RequestPostProcessor con(String sub, String rol) {
        return jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    @BeforeEach
    void setUp() {
        solicitudRepository.deleteAll();
        perfilRepository.deleteAll();
        when(georefClient.geolocalizar(any(), any(), any()))
                .thenReturn(Optional.of(new Coordenadas(-31.41, -64.19)));
        when(animalesClient.obtener(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return new AnimalDto(id, "Animal-" + id, EstadoAnimal.DISPONIBLE, true, true, false);
        });
        when(animalesClient.cambiarEstado(anyLong(), any(), anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            EstadoAnimal est = inv.getArgument(1);
            return new AnimalDto(id, "Animal-" + id, est, true, true, false);
        });
    }

    private long crearSolicitudComo(String sub, long animalId) throws Exception {
        mvc.perform(put("/api/adoptantes/me/perfil").with(con(sub, "ADOPTANTE"))
                        .contentType(MediaType.APPLICATION_JSON).content(PERFIL_OK))
                .andExpect(status().isOk());
        String resp = mvc.perform(post("/api/solicitudes").with(con(sub, "ADOPTANTE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"animalId\":" + animalId + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.id")).longValue();
    }

    @Test
    void idorOtroAdoptanteRecibe403EnDetalleHistorialCancelarYDevolucion() throws Exception {
        long id = crearSolicitudComo("duenio-1", 100L);

        // Detalle, historial y cancelar de solicitud ajena -> 403
        mvc.perform(get("/api/solicitudes/{id}", id).with(con("intruso-2", "ADOPTANTE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(get("/api/solicitudes/{id}/historial", id).with(con("intruso-2", "ADOPTANTE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(post("/api/solicitudes/{id}/cancelar", id).with(con("intruso-2", "ADOPTANTE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // Llevar a EN_ADAPTACION y verificar IDOR en devolucion -> 403
        mvc.perform(post("/api/solicitudes/{id}/aprobar", id).with(con("coord-1", "COORDINADOR")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/solicitudes/{id}/confirmar-entrega", id).with(con("coord-1", "COORDINADOR")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/solicitudes/{id}/devolucion", id).with(con("intruso-2", "ADOPTANTE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Intento ajeno\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void matrizRbacNegativaRechazaSinTokenYRolesCruzados() throws Exception {
        // Sin token -> 401
        mvc.perform(get("/api/solicitudes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/solicitudes/reportes")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/solicitudes/reconciliacion")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/adoptantes/me/perfil")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());

        // ADOPTANTE intentando acciones de COORDINADOR o SERVICIO -> 403
        mvc.perform(get("/api/adoptantes/duenio-1/perfil").with(con("adop-1", "ADOPTANTE"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/solicitudes/1/aprobar").with(con("adop-1", "ADOPTANTE"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/solicitudes/1/rechazar").with(con("adop-1", "ADOPTANTE"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/solicitudes/1/confirmar-entrega").with(con("adop-1", "ADOPTANTE"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/solicitudes/1/cancelar-entrega").with(con("adop-1", "ADOPTANTE"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/solicitudes/1/control-final").with(con("adop-1", "ADOPTANTE"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"resultado\":\"CONFORME\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/solicitudes/reportes").with(con("adop-1", "ADOPTANTE"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/solicitudes/reconciliacion").with(con("adop-1", "ADOPTANTE"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(con("adop-1", "ADOPTANTE"))).andExpect(status().isForbidden());

        // COORDINADOR intentando acciones exclusivas de ADOPTANTE o SERVICIO -> 403
        mvc.perform(get("/api/adoptantes/me/perfil").with(con("coord-1", "COORDINADOR"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/adoptantes/me/perfil").with(con("coord-1", "COORDINADOR"))
                .contentType(MediaType.APPLICATION_JSON).content(PERFIL_OK)).andExpect(status().isForbidden());
        mvc.perform(post("/api/solicitudes").with(con("coord-1", "COORDINADOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"animalId\":1}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/solicitudes/1/cancelar").with(con("coord-1", "COORDINADOR"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/solicitudes/1/devolucion").with(con("coord-1", "COORDINADOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(con("coord-1", "COORDINADOR"))).andExpect(status().isForbidden());

        // SERVICIO intentando la API publica -> 403
        mvc.perform(get("/api/solicitudes").with(con("svc", "SERVICIO"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/adoptantes/me/perfil").with(con("svc", "SERVICIO"))).andExpect(status().isForbidden());
    }

    @Test
    void probesLivenessYReadinessRespondenUpSinExponerDetallesInternos() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}

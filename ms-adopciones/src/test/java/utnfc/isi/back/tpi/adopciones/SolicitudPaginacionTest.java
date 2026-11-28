package utnfc.isi.back.tpi.adopciones;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import utnfc.isi.back.tpi.adopciones.model.Solicitud;
import utnfc.isi.back.tpi.adopciones.repository.SolicitudRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica paginacion aditiva en GET /api/solicitudes: body JSON array, header X-Total-Count,
 * respeto por permisos/filtros y validacion 400 de page/size.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
class SolicitudPaginacionTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SolicitudRepository repository;

    private static RequestPostProcessor con(String sub, String rol) {
        return jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    @BeforeEach
    void sembrar() {
        repository.deleteAll();
        LocalDateTime base = LocalDateTime.of(2026, 6, 1, 10, 0);
        // 3 solicitudes de adoptante-1 (2 rechazadas + 1 pendiente) y 2 de adoptante-2 (1 rechazada + 1 pendiente)
        repository.save(Solicitud.rechazadaPorIncompatibilidad(1L, "A1", "adoptante-1", base, "R-04"));
        repository.save(Solicitud.rechazadaPorIncompatibilidad(2L, "A2", "adoptante-1", base.plusMinutes(1), "R-04"));
        repository.save(Solicitud.pendiente(3L, "A3", "adoptante-1", base.plusMinutes(2), 5.0, false, false, List.of("Sin patio")));
        repository.save(Solicitud.rechazadaPorIncompatibilidad(4L, "A4", "adoptante-2", base.plusMinutes(3), "R-04"));
        repository.save(Solicitud.pendiente(5L, "A5", "adoptante-2", base.plusMinutes(4), 8.0, false, false, List.of()));
        repository.flush();
    }

    @Test
    void sinParametrosDevuelveArregloOrdenadoDescYTotal() throws Exception {
        mvc.perform(get("/api/solicitudes").with(con("coord-1", "COORDINADOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "5"))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].animalNombre").value("A5"))
                .andExpect(jsonPath("$[4].animalNombre").value("A1"));
    }

    @Test
    void pageYSizeAcotanResultadosYRespetanPermisosYFiltro() throws Exception {
        mvc.perform(get("/api/solicitudes").param("page", "0").param("size", "2")
                        .with(con("coord-1", "COORDINADOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "5"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].animalNombre").value("A5"))
                .andExpect(jsonPath("$[1].animalNombre").value("A4"));

        // Adoptante solo ve sus 3 solicitudes
        mvc.perform(get("/api/solicitudes").param("page", "0").param("size", "2")
                        .with(con("adoptante-1", "ADOPTANTE")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "3"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].animalNombre").value("A3"))
                .andExpect(jsonPath("$[1].animalNombre").value("A2"));

        // Filtro por estado combinado con paginacion
        mvc.perform(get("/api/solicitudes").param("estado", "PENDIENTE").param("page", "0").param("size", "1")
                        .with(con("coord-1", "COORDINADOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].animalNombre").value("A5"));
    }

    @Test
    void parametrosInvalidosDevuelven400ConErrorResponse() throws Exception {
        mvc.perform(get("/api/solicitudes").param("size", "101").with(con("coord-1", "COORDINADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errores[0].campo").value("size"));

        mvc.perform(get("/api/solicitudes").param("page", "-1").with(con("adoptante-1", "ADOPTANTE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errores[0].campo").value("page"));
    }
}

package utnfc.isi.back.tpi.donaciones;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import utnfc.isi.back.tpi.donaciones.model.Donacion;
import utnfc.isi.back.tpi.donaciones.repository.DonacionRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica paginacion aditiva en GET /api/donaciones: body JSON array, header X-Total-Count,
 * respeto por permisos (D-04) y validacion 400 de page/size.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DonacionPaginacionTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DonacionRepository repository;

    private static RequestPostProcessor con(String sub, String rol) {
        return jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    @BeforeEach
    void sembrar() {
        repository.deleteAll();
        LocalDateTime base = LocalDateTime.of(2026, 6, 1, 10, 0);
        repository.save(new Donacion("ref-1", "donante-1", new BigDecimal("100.00"), "p1", "https://mp/1", base));
        repository.save(new Donacion("ref-2", "donante-1", new BigDecimal("200.00"), "p2", "https://mp/2", base.plusMinutes(1)));
        repository.save(new Donacion("ref-3", "donante-1", new BigDecimal("300.00"), "p3", "https://mp/3", base.plusMinutes(2)));
        repository.save(new Donacion("ref-4", "donante-2", new BigDecimal("400.00"), "p4", "https://mp/4", base.plusMinutes(3)));
        repository.save(new Donacion("ref-5", "donante-2", new BigDecimal("500.00"), "p5", "https://mp/5", base.plusMinutes(4)));
        repository.flush();
    }

    @Test
    void sinParametrosDevuelveArregloOrdenadoDescYTotal() throws Exception {
        mvc.perform(get("/api/donaciones").with(con("coord-1", "COORDINADOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "5"))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].monto").value(500.0))
                .andExpect(jsonPath("$[4].monto").value(100.0));
    }

    @Test
    void pageYSizeAcotanResultadosYRespetanPertenencia() throws Exception {
        mvc.perform(get("/api/donaciones").param("page", "0").param("size", "2")
                        .with(con("coord-1", "COORDINADOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "5"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].monto").value(500.0))
                .andExpect(jsonPath("$[1].monto").value(400.0));

        mvc.perform(get("/api/donaciones").param("page", "0").param("size", "2")
                        .with(con("donante-1", "ADOPTANTE")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "3"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].monto").value(300.0))
                .andExpect(jsonPath("$[1].monto").value(200.0));
    }

    @Test
    void parametrosInvalidosDevuelven400ConErrorResponse() throws Exception {
        mvc.perform(get("/api/donaciones").param("size", "101").with(con("coord-1", "COORDINADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errores[0].campo").value("size"));

        mvc.perform(get("/api/donaciones").param("size", "0").with(con("donante-1", "ADOPTANTE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mvc.perform(get("/api/donaciones").param("page", "-1").with(con("donante-1", "ADOPTANTE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errores[0].campo").value("page"));
    }
}

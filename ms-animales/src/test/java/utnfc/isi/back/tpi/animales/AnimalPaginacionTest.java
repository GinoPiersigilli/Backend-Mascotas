package utnfc.isi.back.tpi.animales;

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
import utnfc.isi.back.tpi.animales.model.Animal;
import utnfc.isi.back.tpi.animales.model.Especie;
import utnfc.isi.back.tpi.animales.model.Tamanio;
import utnfc.isi.back.tpi.animales.repository.AnimalRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica paginacion aditiva en GET /api/animales: body JSON array, header X-Total-Count,
 * filtros combinados y validacion 400 de page/size.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
class AnimalPaginacionTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AnimalRepository repository;

    private static RequestPostProcessor adoptante() {
        return jwt().jwt(j -> j.subject("adoptante-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADOPTANTE"));
    }

    @BeforeEach
    void sembrar() {
        repository.deleteAll();
        repository.save(new Animal("A1", Especie.PERRO, 12, Tamanio.MEDIANO, null, true, true, false));
        repository.save(new Animal("A2", Especie.PERRO, 24, Tamanio.GRANDE, null, true, true, true));
        repository.save(new Animal("A3", Especie.GATO, 10, Tamanio.PEQUENIO, null, true, true, false));
        Animal a4 = new Animal("A4", Especie.PERRO, 36, Tamanio.MEDIANO, null, true, true, false);
        a4.marcarNoDisponible("Observacion veterinaria");
        repository.save(a4);
        repository.save(new Animal("A5", Especie.GATO, 18, Tamanio.PEQUENIO, null, true, true, false));
        repository.flush();
    }

    @Test
    void sinParametrosDevuelveArregloCompletoYTotal() throws Exception {
        mvc.perform(get("/api/animales").with(adoptante()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "5"))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].nombre").value("A1"))
                .andExpect(jsonPath("$[4].nombre").value("A5"));
    }

    @Test
    void pageYSizeAcotanResultadosConOrdenEstableYTotalReal() throws Exception {
        mvc.perform(get("/api/animales").param("page", "0").param("size", "2").with(adoptante()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "5"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].nombre").value("A1"))
                .andExpect(jsonPath("$[1].nombre").value("A2"));

        mvc.perform(get("/api/animales").param("page", "2").param("size", "2").with(adoptante()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "5"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nombre").value("A5"));
    }

    @Test
    void filtrosSeCombinanConPaginacionYTotalFiltrado() throws Exception {
        mvc.perform(get("/api/animales")
                        .param("estado", "DISPONIBLE")
                        .param("especie", "PERRO")
                        .param("page", "0")
                        .param("size", "1")
                        .with(adoptante()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nombre").value("A1"));
    }

    @Test
    void parametrosInvalidosDevuelven400ConErrorResponse() throws Exception {
        mvc.perform(get("/api/animales").param("size", "101").with(adoptante()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errores[0].campo").value("size"));

        mvc.perform(get("/api/animales").param("size", "0").with(adoptante()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mvc.perform(get("/api/animales").param("page", "-1").with(adoptante()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errores[0].campo").value("page"));
    }
}

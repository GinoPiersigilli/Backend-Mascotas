package utnfc.isi.back.tpi.animales;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import utnfc.isi.back.tpi.animales.model.Animal;
import utnfc.isi.back.tpi.animales.model.Especie;
import utnfc.isi.back.tpi.animales.model.EstadoAnimal;
import utnfc.isi.back.tpi.animales.model.Tamanio;
import utnfc.isi.back.tpi.animales.repository.AnimalRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica que las transiciones internas de ms-animales sean idempotentes cuando se informa
 * solicitudId (misma solicitud -> 200 sin efecto; otra solicitud -> 409 Conflict).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnimalIdempotenciaTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AnimalRepository repository;

    private Long animalId;

    private static RequestPostProcessor servicio() {
        return jwt().jwt(j -> j.subject("adopciones-service")).authorities(new SimpleGrantedAuthority("ROLE_SERVICIO"));
    }

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        Animal guardado = repository.saveAndFlush(new Animal(
                "Luna", Especie.PERRO, 24, Tamanio.MEDIANO, null, true, true, false));
        animalId = guardado.getId();
    }

    @Test
    void reintentoMismaSolicitudEsIdempotenteYOtraSolicitudResponde409() throws Exception {
        // 1) Primera reserva con solicitudId=42 -> 200 RESERVADO
        mvc.perform(put("/internal/animales/" + animalId + "/estado").with(servicio())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nuevoEstado\":\"RESERVADO\",\"solicitudId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RESERVADO"));

        long versionTrasReserva = repository.findById(animalId).orElseThrow().getVersion();

        // 2) Reintento de RESERVADO con la misma solicitudId=42 -> 200 sin efecto (ni sube version)
        mvc.perform(put("/internal/animales/" + animalId + "/estado").with(servicio())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nuevoEstado\":\"RESERVADO\",\"solicitudId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RESERVADO"));

        assertThat(repository.findById(animalId).orElseThrow().getVersion()).isEqualTo(versionTrasReserva);

        // 3) Otra solicitud (99) intenta reservar o avanzar el mismo animal -> 409 Conflict
        mvc.perform(put("/internal/animales/" + animalId + "/estado").with(servicio())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nuevoEstado\":\"RESERVADO\",\"solicitudId\":99}"))
                .andExpect(status().isConflict());

        mvc.perform(put("/internal/animales/" + animalId + "/estado").with(servicio())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nuevoEstado\":\"EN_ADAPTACION\",\"solicitudId\":99}"))
                .andExpect(status().isConflict());

        // 4) Liberacion a DISPONIBLE y reintento idempotente sobre DISPONIBLE
        mvc.perform(put("/internal/animales/" + animalId + "/estado").with(servicio())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nuevoEstado\":\"DISPONIBLE\",\"solicitudId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("DISPONIBLE"));

        mvc.perform(put("/internal/animales/" + animalId + "/estado").with(servicio())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nuevoEstado\":\"DISPONIBLE\",\"solicitudId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("DISPONIBLE"));

        Animal liberado = repository.findById(animalId).orElseThrow();
        assertThat(liberado.getEstado()).isEqualTo(EstadoAnimal.DISPONIBLE);
        assertThat(liberado.getSolicitudActivaId()).isNull();
    }
}

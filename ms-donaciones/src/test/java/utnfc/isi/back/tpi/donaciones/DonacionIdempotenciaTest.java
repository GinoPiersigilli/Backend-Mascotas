package utnfc.isi.back.tpi.donaciones;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import utnfc.isi.back.tpi.donaciones.client.MercadoPagoClient;
import utnfc.isi.back.tpi.donaciones.repository.DonacionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica Idempotency-Key opcional en POST /api/donaciones (ADR-18):
 * - misma clave y mismo monto (incluso 500 vs 500.00): devuelve la misma donacion sin cobrar dos veces;
 * - misma clave y distinto monto: responde 409 Conflict con ErrorResponse;
 * - distinto donante con la misma clave: aislamiento por donante (sin fuga IDOR);
 * - clave demasiado larga: 400 Bad Request;
 * - sin clave: se crean donaciones independientes (retrocompatibilidad).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
class DonacionIdempotenciaTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DonacionRepository repository;

    @MockitoBean
    private MercadoPagoClient mercadoPago;

    private static RequestPostProcessor adoptante(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority("ROLE_ADOPTANTE"));
    }

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        reset(mercadoPago);
        when(mercadoPago.crearCobro(anyString(), any(), anyString()))
                .thenReturn(new MercadoPagoClient.Cobro("pref-idem", "https://mp.test/checkout?pref_id=pref-idem"));
    }

    @Test
    void mismaClaveYMismoMontoDevuelvenMismaDonacionSinCobrarDosVeces() throws Exception {
        String primera = mvc.perform(post("/api/donaciones").with(adoptante("ana"))
                        .header("Idempotency-Key", "idem-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monto\":500}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id1 = ((Number) JsonPath.read(primera, "$.id")).longValue();

        String reintento = mvc.perform(post("/api/donaciones").with(adoptante("ana"))
                        .header("Idempotency-Key", "idem-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monto\":500.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id1))
                .andExpect(jsonPath("$.linkPago").value("https://mp.test/checkout?pref_id=pref-idem"))
                .andReturn().getResponse().getContentAsString();

        assertThat(((Number) JsonPath.read(reintento, "$.id")).longValue()).isEqualTo(id1);
        assertThat(repository.count()).isEqualTo(1);
        verify(mercadoPago, times(1)).crearCobro(anyString(), any(), anyString());
    }

    @Test
    void mismaClaveConDistintoMontoResponde409() throws Exception {
        mvc.perform(post("/api/donaciones").with(adoptante("ana"))
                        .header("Idempotency-Key", "idem-key-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monto\":500}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/donaciones").with(adoptante("ana"))
                        .header("Idempotency-Key", "idem-key-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monto\":750}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflicto"))
                .andExpect(jsonPath("$.path").value("/api/donaciones"));

        assertThat(repository.count()).isEqualTo(1);
        verify(mercadoPago, times(1)).crearCobro(anyString(), any(), anyString());
    }

    @Test
    void laClaveEstaAcotadaPorDonanteYNoFiltraDatosAjenos() throws Exception {
        String deAna = mvc.perform(post("/api/donaciones").with(adoptante("ana"))
                        .header("Idempotency-Key", "clave-compartida")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monto\":500}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idAna = ((Number) JsonPath.read(deAna, "$.id")).longValue();

        String deBeto = mvc.perform(post("/api/donaciones").with(adoptante("beto"))
                        .header("Idempotency-Key", "clave-compartida")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monto\":300}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.donanteId").value("beto"))
                .andReturn().getResponse().getContentAsString();
        long idBeto = ((Number) JsonPath.read(deBeto, "$.id")).longValue();

        assertThat(idBeto).isNotEqualTo(idAna);
        assertThat(repository.count()).isEqualTo(2);
        verify(mercadoPago, times(2)).crearCobro(anyString(), any(), anyString());
    }

    @Test
    void sinClaveCreaDonacionesIndependientesYClaveExcesivaResponde400() throws Exception {
        mvc.perform(post("/api/donaciones").with(adoptante("ana"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monto\":100}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/donaciones").with(adoptante("ana"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monto\":100}"))
                .andExpect(status().isCreated());
        assertThat(repository.count()).isEqualTo(2);

        String claveLarga = "k".repeat(129);
        mvc.perform(post("/api/donaciones").with(adoptante("ana"))
                        .header("Idempotency-Key", claveLarga)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monto\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}

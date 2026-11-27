package utnfc.isi.back.tpi.adopciones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Valida la especificacion OpenAPI de ms-adopciones y la exporta a docs/openapi/ms-adopciones.json
 * cuando se ejecuta con -Pexport-openapi (-Dopenapi.export=true).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiExportTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void especificacionOpenApiCompletaYSincronizada() throws Exception {
        String raw = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        ObjectMapper mapper = JsonMapper.builder()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .build();
        JsonNode root = mapper.readTree(raw);

        assertThat(root.path("openapi").asText()).startsWith("3.");
        assertThat(root.path("components").path("securitySchemes").has("bearer-jwt")).isTrue();
        assertThat(root.path("paths").has("/api/solicitudes")).isTrue();
        assertThat(root.path("paths").path("/api/solicitudes/{id}/aprobar")
                .path("post").path("responses").has("503")).isTrue();
        assertThat(root.path("paths").path("/api/solicitudes/{id}/aprobar")
                .path("post").path("responses").has("409")).isTrue();
        assertThat(root.path("paths").has("/api/adoptantes/me/perfil")).isTrue();

        Object ordenado = mapper.treeToValue(root, Object.class);
        String formateado = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(ordenado)
                .replace("\r\n", "\n") + "\n";

        Path destino = Path.of("..", "docs", "openapi", "ms-adopciones.json").normalize();
        if (Boolean.getBoolean("openapi.export") || !Files.exists(destino)) {
            Files.createDirectories(destino.getParent());
            Files.writeString(destino, formateado, StandardCharsets.UTF_8);
        } else {
            String actual = Files.readString(destino, StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertThat(actual)
                    .as("docs/openapi/ms-adopciones.json desactualizado; ejecutar: mvn -B test -Pexport-openapi -Dtest=OpenApiExportTest")
                    .isEqualTo(formateado);
        }
    }
}

package utnfc.isi.back.tpi.common.error;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utnfc.isi.back.tpi.common.exception.AccesoDenegadoException;
import utnfc.isi.back.tpi.common.exception.ConflictoException;
import utnfc.isi.back.tpi.common.exception.RecursoNoEncontradoException;
import utnfc.isi.back.tpi.common.exception.ServicioNoDisponibleException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica que cada excepcion del contrato se traduzca al codigo HTTP y al formato acordados.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @RestController
    static class ControladorDePrueba {
        @GetMapping("/error/{tipo}")
        String lanzar(@PathVariable String tipo) {
            switch (tipo) {
                case "404" -> throw new RecursoNoEncontradoException("No existe el animal 7");
                case "409" -> throw new ConflictoException("El animal 7 no esta disponible");
                case "403" -> throw new AccesoDenegadoException("La solicitud pertenece a otro adoptante");
                case "503" -> throw new ServicioNoDisponibleException("Animales no responde");
                default -> throw new IllegalStateException("detalle interno que no debe filtrarse");
            }
        }

        @PostMapping("/validar")
        String validar(@Valid @RequestBody Cuerpo cuerpo) {
            return "ok";
        }

        @GetMapping("/paginado")
        String paginado(@RequestParam(defaultValue = "0") @PositiveOrZero int page,
                        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
            return "ok";
        }

        @GetMapping("/constraint")
        String constraint() {
            Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
            throw new ConstraintViolationException(validator.validate(new Cuerpo("", null)));
        }
    }

    enum Categoria { A, B }

    record Cuerpo(@NotBlank(message = "es obligatorio") String nombre, Categoria categoria) {
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ControladorDePrueba())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void recursoNoEncontradoDevuelve404() throws Exception {
        mvc.perform(get("/error/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("No existe el animal 7"))
                .andExpect(jsonPath("$.path").value("/error/404"));
    }

    @Test
    void conflictoDevuelve409ConTextoEnCastellano() throws Exception {
        mvc.perform(get("/error/409"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflicto"));
    }

    @Test
    void accesoDenegadoDevuelve403() throws Exception {
        mvc.perform(get("/error/403")).andExpect(status().isForbidden());
    }

    @Test
    void servicioNoDisponibleDevuelve503() throws Exception {
        mvc.perform(get("/error/503")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void errorInesperadoNoExponeDetalles() throws Exception {
        mvc.perform(get("/error/otro"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Error interno del servidor"));
    }

    @Test
    void validacionDevuelve400ConListaDeCampos() throws Exception {
        mvc.perform(post("/validar").contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("nombre"))
                .andExpect(jsonPath("$.errores[0].mensaje").value("es obligatorio"));
    }

    @Test
    void jsonInvalidoDevuelve400() throws Exception {
        mvc.perform(post("/validar").contentType(MediaType.APPLICATION_JSON).content("{no es json"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/validar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"ok\",\"categoria\":\"INVALIDA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("categoria"))
                .andExpect(jsonPath("$.errores[0].mensaje").value(org.hamcrest.Matchers.containsString("A, B")));
    }

    @Test
    void validacionDeParametrosDevuelve400ConFormatoUnico() throws Exception {
        mvc.perform(get("/paginado").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errores[0].campo").value("size"));

        mvc.perform(get("/paginado").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errores[0].campo").value("page"));
    }

    @Test
    void constraintViolationDevuelve400ConListaDeCampos() throws Exception {
        mvc.perform(get("/constraint"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errores[0].campo").value("nombre"))
                .andExpect(jsonPath("$.errores[0].mensaje").value("es obligatorio"));
    }
}

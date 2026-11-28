package ar.org.refugio.common.error;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import ar.org.refugio.common.exception.AccesoDenegadoException;
import ar.org.refugio.common.exception.ConflictoException;
import ar.org.refugio.common.exception.RecursoNoEncontradoException;
import ar.org.refugio.common.exception.ServicioNoDisponibleException;
import ar.org.refugio.common.exception.SolicitudInvalidaException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica que cada excepcion del contrato se traduzca al codigo HTTP y al formato acordados.
 */
@Tag("unit")
class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @RestController
    static class ControladorDePrueba {
        @GetMapping("/error/{tipo}")
        String lanzar(@PathVariable String tipo) {
            switch (tipo) {
                case "400" -> throw new SolicitudInvalidaException("Monto fuera de rango");
                case "401" -> throw new BadCredentialsException("token vencido");
                case "403-spring" -> throw new AccessDeniedException("rol insuficiente");
                case "404" -> throw new RecursoNoEncontradoException("No existe el animal 7");
                case "409" -> throw new ConflictoException("El animal 7 no esta disponible");
                case "409-lock" -> throw new OptimisticLockingFailureException("version desactualizada");
                case "409-integrity" -> throw new DataIntegrityViolationException("unique violation");
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

        @GetMapping("/requerido")
        String requerido(@RequestParam Categoria categoria, @RequestParam int limite) {
            return categoria + ":" + limite;
        }

        @GetMapping("/constraint")
        String constraint() {
            Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
            throw new ConstraintViolationException(validator.validate(new Cuerpo("", null, null)));
        }
    }

    enum Categoria { A, B }

    record Item(int cantidad) {
    }

    record Cuerpo(@NotBlank(message = "es obligatorio") String nombre, Categoria categoria, List<Item> items) {
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

    @Test
    void solicitudInvalidaYSeguridadDeSpringDevuelven400_401Y403() throws Exception {
        mvc.perform(get("/error/400"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Solicitud invalida"))
                .andExpect(jsonPath("$.message").value("Monto fuera de rango"));

        mvc.perform(get("/error/401"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No autenticado"));

        mvc.perform(get("/error/403-spring"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Prohibido"));
    }

    @Test
    void concurrenciaEIntegridadDevuelven409() throws Exception {
        mvc.perform(get("/error/409-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflicto"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("otra operacion")));

        mvc.perform(get("/error/409-integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflicto"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("integridad")));
    }

    @Test
    void parametroTipoIncorrectoYFaltanteDevuelven400() throws Exception {
        mvc.perform(get("/requerido").param("categoria", "INVALIDA").param("limite", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("categoria"))
                .andExpect(jsonPath("$.errores[0].mensaje").value(org.hamcrest.Matchers.containsString("A, B")));

        mvc.perform(get("/requerido").param("categoria", "A").param("limite", "no-numero"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("limite"))
                .andExpect(jsonPath("$.errores[0].mensaje").value("valor invalido"));

        mvc.perform(get("/requerido").param("limite", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Falta el parametro 'categoria'"));
    }

    @Test
    void metodoNoPermitidoMediaTypeYJsonAnidadoInvalido() throws Exception {
        mvc.perform(delete("/validar"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("Metodo no permitido"));

        mvc.perform(post("/validar").contentType(MediaType.TEXT_PLAIN).content("hola"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("Tipo de contenido no soportado"));

        mvc.perform(post("/validar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"ok\",\"items\":[{\"cantidad\":\"abc\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("items[0].cantidad"))
                .andExpect(jsonPath("$.errores[0].mensaje").value("tiene un tipo o formato invalido"));
    }

    @Test
    void rutaInexistenteErrorResponseYConstructoresConCausa() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/no-existe");

        ResponseEntity<ErrorResponse> resp = handler.handleRutaInexistente(
                new NoResourceFoundException(HttpMethod.GET, "/no-existe"), req);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().message()).isEqualTo("No existe el recurso /no-existe");

        assertThat(ErrorResponse.descripcion(HttpStatus.GATEWAY_TIMEOUT)).isEqualTo("Tiempo de espera agotado");
        assertThat(ErrorResponse.descripcion(HttpStatus.TOO_MANY_REQUESTS))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());

        RuntimeException causa = new RuntimeException("raiz");
        assertThat(new SolicitudInvalidaException("msg", causa).getCause()).isSameAs(causa);
        assertThat(new ConflictoException("msg", causa).getCause()).isSameAs(causa);
        assertThat(new RecursoNoEncontradoException("msg", causa).getCause()).isSameAs(causa);
        assertThat(new ServicioNoDisponibleException("msg", causa).getCause()).isSameAs(causa);
    }
}

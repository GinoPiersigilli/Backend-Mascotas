package utnfc.isi.back.tpi.gateway.config;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;
import utnfc.isi.back.tpi.gateway.filter.CorrelationIdGlobalFilter;

/**
 * Conecta el Context de Reactor con el MDC de SLF4J para la clave {@code correlationId},
 * de modo que cualquier linea de log emitida en el pipeline reactivo del Gateway conserve
 * el mismo formato {@code [gateway] [X-Correlation-ID]} que los microservicios MVC.
 */
@Configuration
public class CorrelationContextConfig {

    @PostConstruct
    void configurarPropagacionDeMdc() {
        Hooks.enableAutomaticContextPropagation();
        ContextRegistry.getInstance().registerThreadLocalAccessor(
                CorrelationIdGlobalFilter.MDC_KEY,
                () -> MDC.get(CorrelationIdGlobalFilter.MDC_KEY),
                valor -> MDC.put(CorrelationIdGlobalFilter.MDC_KEY, valor),
                () -> MDC.remove(CorrelationIdGlobalFilter.MDC_KEY)
        );
    }
}

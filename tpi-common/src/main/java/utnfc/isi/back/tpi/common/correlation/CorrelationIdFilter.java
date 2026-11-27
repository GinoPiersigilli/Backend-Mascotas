package utnfc.isi.back.tpi.common.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Lee el encabezado X-Correlation-ID (lo genera el Gateway), lo deja en el MDC para que
 * aparezca en cada linea de log y lo devuelve en la respuesta. Si la llamada no paso por el
 * Gateway (por ejemplo, en desarrollo) o trae caracteres no permitidos, genera uno nuevo.
 *
 * Se ejecuta antes que la cadena de Spring Security para que tambien los 401/403 queden trazados.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern PATRON_SEGURO = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = sanitizar(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String sanitizar(String recibido) {
        if (recibido == null || !PATRON_SEGURO.matcher(recibido).matches()) {
            return UUID.randomUUID().toString();
        }
        return new String(recibido.toCharArray());
    }
}

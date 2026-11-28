package ar.org.refugio.adopciones.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Estados de una solicitud de adopcion (documento de dominio, seccion 3.6).
 *
 * PENDIENTE -> APROBADA -> EN_ADAPTACION -> FINALIZADA
 *          \-> RECHAZADA        \-> CANCELADA (entrega cancelada o plazo vencido)
 *          \-> CANCELADA         EN_ADAPTACION -> REVERTIDA (control desfavorable o devolucion)
 */
public enum EstadoSolicitud {
    PENDIENTE,
    APROBADA,
    RECHAZADA,
    CANCELADA,
    EN_ADAPTACION,
    FINALIZADA,
    REVERTIDA;

    private static final Set<EstadoSolicitud> ACTIVAS =
            Collections.unmodifiableSet(EnumSet.of(PENDIENTE, APROBADA, EN_ADAPTACION));

    /** R-02: una persona puede tener a lo sumo una solicitud activa a la vez. */
    public boolean esActiva() {
        return this == PENDIENTE || this == APROBADA || this == EN_ADAPTACION;
    }

    /** Conjunto inmutable de estados que mantienen bloqueado al adoptante (R-02). */
    public static Set<EstadoSolicitud> activas() {
        return ACTIVAS;
    }
}

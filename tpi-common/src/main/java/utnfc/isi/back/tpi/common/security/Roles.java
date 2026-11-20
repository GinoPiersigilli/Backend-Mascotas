package utnfc.isi.back.tpi.common.security;

import org.springframework.security.core.Authentication;

/**
 * Nombres de los roles de realm definidos en Keycloak (CONTRATOS.md, seccion 3.1).
 * Se comparten para que los tres servicios usen exactamente los mismos textos.
 */
public final class Roles {

    public static final String PREFIJO = "ROLE_";
    public static final String ADOPTANTE = "ADOPTANTE";
    public static final String COORDINADOR = "COORDINADOR";
    public static final String SERVICIO = "SERVICIO";

    private Roles() {
    }

    /** Indica si la autenticacion posee el rol de realm indicado (con el prefijo de Spring Security). */
    public static boolean tieneRol(Authentication auth, String rol) {
        if (auth == null || rol == null) {
            return false;
        }
        String esperado = PREFIJO + rol;
        return auth.getAuthorities().stream().anyMatch(a -> esperado.equals(a.getAuthority()));
    }
}

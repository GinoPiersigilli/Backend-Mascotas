package ar.org.refugio.donaciones;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Se escanea tambien el paquete de la libreria comun para tomar su manejador de errores,
 * el filtro de correlacion y los manejadores de 401/403.
 * EnableScheduling activa la reconciliacion periodica de donaciones pendientes (F2).
 */
@SpringBootApplication(scanBasePackages = {"ar.org.refugio.donaciones", "ar.org.refugio.common"})
@ConfigurationPropertiesScan
@EnableScheduling
public class DonacionesApplication {

    public static void main(String[] args) {
        SpringApplication.run(DonacionesApplication.class, args);
    }
}

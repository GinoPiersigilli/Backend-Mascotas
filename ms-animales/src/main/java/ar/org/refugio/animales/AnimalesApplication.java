package ar.org.refugio.animales;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Se escanea tambien el paquete de la libreria comun para tomar su manejador de errores.
 */
@SpringBootApplication(scanBasePackages = {"ar.org.refugio.animales", "ar.org.refugio.common"})
public class AnimalesApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnimalesApplication.class, args);
    }
}

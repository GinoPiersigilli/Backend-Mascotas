package ar.org.refugio.adopciones.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listener en proceso para los eventos de dominio de adopcion (sin broker externo).
 * Registra traza estructurada sin PII (solo identificadores tecnicos y marcas de negocio).
 */
@Component
public class EventosAdopcionListener {

    private static final Logger log = LoggerFactory.getLogger(EventosAdopcionListener.class);

    @EventListener
    public void alAprobarSolicitud(SolicitudAprobadaEvent ev) {
        log.info("evento=SolicitudAprobada solicitudId={} animalId={} conExcepcion={} fecha={}",
                ev.solicitudId(), ev.animalId(), ev.conExcepcion(), ev.fecha());
    }

    @EventListener
    public void alConfirmarEntrega(EntregaConfirmadaEvent ev) {
        log.info("evento=EntregaConfirmada solicitudId={} animalId={} fechaEntrega={} fechaControl={}",
                ev.solicitudId(), ev.animalId(), ev.fechaEntrega(), ev.fechaControl());
    }

    @EventListener
    public void alConfirmarAdopcion(AdopcionConfirmadaEvent ev) {
        log.info("evento=AdopcionConfirmada solicitudId={} animalId={} fecha={}",
                ev.solicitudId(), ev.animalId(), ev.fecha());
    }
}

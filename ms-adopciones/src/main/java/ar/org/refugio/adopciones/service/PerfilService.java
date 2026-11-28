package ar.org.refugio.adopciones.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ar.org.refugio.adopciones.dto.PerfilRequest;
import ar.org.refugio.adopciones.dto.PerfilResponse;
import ar.org.refugio.adopciones.model.Perfil;
import ar.org.refugio.adopciones.repository.PerfilRepository;
import ar.org.refugio.common.exception.RecursoNoEncontradoException;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class PerfilService {

    private final PerfilRepository repository;
    private final Clock clock;

    public PerfilService(PerfilRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Crea o reemplaza el perfil del usuario autenticado (PUT idempotente sobre /me/perfil). */
    @Transactional
    public PerfilResponse guardar(String adoptanteId, PerfilRequest req) {
        Perfil perfil = repository.findById(adoptanteId).orElseGet(() -> new Perfil(adoptanteId));
        perfil.actualizar(req.nombreCompleto().trim(), req.telefono().trim(), req.provincia().trim(),
                req.localidad().trim(), req.direccion().trim(), req.tipoVivienda(), req.tienePatio(),
                req.hayNinios(), req.hayOtrosAnimales(), LocalDateTime.now(clock));
        return PerfilResponse.desde(repository.save(perfil));
    }

    @Transactional(readOnly = true)
    public PerfilResponse obtener(String adoptanteId) {
        return repository.findById(adoptanteId)
                .map(PerfilResponse::desde)
                .orElseThrow(() -> new RecursoNoEncontradoException("El adoptante " + adoptanteId
                        + " todavia no completo su perfil"));
    }
}

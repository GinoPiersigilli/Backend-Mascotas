package ar.org.refugio.adopciones.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ar.org.refugio.adopciones.model.Perfil;

public interface PerfilRepository extends JpaRepository<Perfil, String> {
}

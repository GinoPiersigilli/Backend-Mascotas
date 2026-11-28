package ar.org.refugio.donaciones.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ar.org.refugio.donaciones.model.Donacion;
import ar.org.refugio.donaciones.model.EstadoDonacion;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DonacionRepository extends JpaRepository<Donacion, Long> {

    Optional<Donacion> findByDonanteIdAndIdempotencyKey(String donanteId, String idempotencyKey);

    Optional<Donacion> findByReferencia(String referencia);

    List<Donacion> findByEstadoAndFechaCreacionBeforeOrderByIdAsc(EstadoDonacion estado, LocalDateTime limite);

    List<Donacion> findAllByOrderByIdDesc();

    List<Donacion> findByDonanteIdOrderByIdDesc(String donanteId);

    Page<Donacion> findByDonanteId(String donanteId, Pageable pageable);

    long countByEstado(EstadoDonacion estado);

    @Query("select coalesce(sum(d.monto), 0) from Donacion d where d.estado = :estado")
    BigDecimal sumarMontoPorEstado(EstadoDonacion estado);
}

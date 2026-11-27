package utnfc.isi.back.tpi.donaciones.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import utnfc.isi.back.tpi.donaciones.model.Donacion;
import utnfc.isi.back.tpi.donaciones.model.EstadoDonacion;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface DonacionRepository extends JpaRepository<Donacion, Long> {

    Optional<Donacion> findByDonanteIdAndIdempotencyKey(String donanteId, String idempotencyKey);

    List<Donacion> findAllByOrderByIdDesc();

    List<Donacion> findByDonanteIdOrderByIdDesc(String donanteId);

    Page<Donacion> findByDonanteId(String donanteId, Pageable pageable);

    long countByEstado(EstadoDonacion estado);

    @Query("select coalesce(sum(d.monto), 0) from Donacion d where d.estado = :estado")
    BigDecimal sumarMontoPorEstado(EstadoDonacion estado);
}

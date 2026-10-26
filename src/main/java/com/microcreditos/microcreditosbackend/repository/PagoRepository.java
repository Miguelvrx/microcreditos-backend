package com.microcreditos.microcreditosbackend.repository;

import com.microcreditos.microcreditosbackend.model.Pago;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PagoRepository extends JpaRepository<Pago, Long> {

    List<Pago> findByCreditoId(Long creditoId);

    List<Pago> findByReferenciaPago(String referenciaPago);
}
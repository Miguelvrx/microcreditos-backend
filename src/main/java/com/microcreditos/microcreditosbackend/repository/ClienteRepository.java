package com.microcreditos.microcreditosbackend.repository;

import com.microcreditos.microcreditosbackend.model.Cliente;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;


public interface ClienteRepository extends JpaRepository<Cliente, Long> {
    Optional<Cliente> findByNumeroDocumento(String numeroDocumento);

    // texto nunca debe ser null (el service lo normaliza a ""): con null,
    // PostgreSQL no puede deducir el tipo del parámetro en LOWER(...).
    @Query("""
            SELECT c FROM Cliente c
            WHERE LOWER(c.nombreCompleto) LIKE LOWER(CONCAT('%', :texto, '%'))
               OR LOWER(c.numeroDocumento) LIKE LOWER(CONCAT('%', :texto, '%'))
               OR LOWER(COALESCE(c.telefono, '')) LIKE LOWER(CONCAT('%', :texto, '%'))
            """)
    Page<Cliente> buscar(@Param("texto") String texto, Pageable pageable);
}


package com.microcreditos.microcreditosbackend.repository;

import com.microcreditos.microcreditosbackend.model.Credito;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface CreditoRepository extends JpaRepository<Credito, Long> {
    List<Credito> findByClienteId(Long clienteId);

    // Usado por el reporte de cartera sin filtros
    List<Credito> findByEstadoIn(List<Credito.EstadoCredito> estados);

    // Usado por el reporte de cartera CON filtros. clienteId/fechaDesde/fechaHasta
    // son opcionales (null = no filtra por ese campo); estados siempre viene
    // resuelto (con default) desde ReporteService antes de llamar acá.
    @Query("""
            SELECT c FROM Credito c
            WHERE c.estado IN :estados
              AND (:clienteId IS NULL OR c.cliente.id = :clienteId)
              AND (:fechaDesde IS NULL OR c.fechaInicio >= :fechaDesde)
              AND (:fechaHasta IS NULL OR c.fechaInicio <= :fechaHasta)
            """)
    List<Credito> buscarParaReporte(
            @Param("estados") List<Credito.EstadoCredito> estados,
            @Param("clienteId") Long clienteId,
            @Param("fechaDesde") LocalDate fechaDesde,
            @Param("fechaHasta") LocalDate fechaHasta);
}
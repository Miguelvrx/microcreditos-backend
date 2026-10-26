package com.microcreditos.microcreditosbackend.repository;

import com.microcreditos.microcreditosbackend.model.Auditoria;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * JpaSpecificationExecutor permite armar la consulta solo con los filtros
 * que el usuario realmente mandó (usuario, acción, fechas), sin parámetros
 * nulos en JPQL que a veces dan problemas de tipos en PostgreSQL.
 */
public interface AuditoriaRepository extends JpaRepository<Auditoria, Long>,
        JpaSpecificationExecutor<Auditoria> {
}   
package com.microcreditos.microcreditosbackend.dto;

import java.util.List;

/**
 * Envoltorio propio de paginación. Se usa en vez de devolver Page<> directo
 * para tener un JSON estable y simple para el frontend.
 */
public record AuditoriaPaginaDTO(
        List<AuditoriaResponseDTO> contenido,
        int pagina,
        int tamano,
        long totalElementos,
        int totalPaginas
) {
}
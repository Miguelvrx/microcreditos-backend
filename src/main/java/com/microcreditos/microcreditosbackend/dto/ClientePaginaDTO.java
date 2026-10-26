package com.microcreditos.microcreditosbackend.dto;

import com.microcreditos.microcreditosbackend.model.Cliente;

import java.util.List;

public record ClientePaginaDTO(
        List<Cliente> contenido,
        int pagina,
        int tamano,
        long totalElementos,
        int totalPaginas) {
}
package com.microcreditos.microcreditosbackend.dto;

// Nunca incluye el password (ni siquiera el hash) — es lo único que
// debe salir del backend cuando se lista o consulta un usuario.
public record UsuarioResponseDTO(
        Long id,
        String nombreUsuario,
        String rol,
        boolean activo
) {}
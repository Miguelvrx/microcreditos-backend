package com.microcreditos.microcreditosbackend.dto;

public record LoginResponse(
        String token,
        String nombreUsuario,
        String rol
) {
}
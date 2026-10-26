package com.microcreditos.microcreditosbackend.dto;

import com.microcreditos.microcreditosbackend.model.RolUsuario;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegistroRequest(
        @NotBlank String nombreUsuario,
        @NotBlank @Size(min = 6, message = "La contraseña debe tener al menos 6 caracteres")
        String password,
        @NotNull(message = "El rol es obligatorio") RolUsuario rol
) {
}
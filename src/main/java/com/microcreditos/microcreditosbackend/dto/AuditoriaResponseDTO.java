package com.microcreditos.microcreditosbackend.dto;

import com.microcreditos.microcreditosbackend.model.AccionAuditoria;
import com.microcreditos.microcreditosbackend.model.Auditoria;

import java.time.LocalDateTime;

public record AuditoriaResponseDTO(
        Long id,
        String usuario,
        AccionAuditoria accion,
        String entidad,
        Long entidadId,
        String detalle,
        LocalDateTime fecha
) {
    public static AuditoriaResponseDTO desde(Auditoria a) {
        return new AuditoriaResponseDTO(
                a.getId(),
                a.getUsuario(),
                a.getAccion(),
                a.getEntidad(),
                a.getEntidadId(),
                a.getDetalle(),
                a.getFecha());
    }
}
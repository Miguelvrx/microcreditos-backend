package com.microcreditos.microcreditosbackend.controller;

import com.microcreditos.microcreditosbackend.dto.AuditoriaPaginaDTO;
import com.microcreditos.microcreditosbackend.dto.AuditoriaResponseDTO;
import com.microcreditos.microcreditosbackend.model.AccionAuditoria;
import com.microcreditos.microcreditosbackend.model.Auditoria;
import com.microcreditos.microcreditosbackend.service.AuditoriaService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Consulta del historial de auditoría. Solo ADMIN
 * (regla "/api/auditoria/**" en SecurityConfig).
 *
 * Ejemplo:
 * GET /api/auditoria?usuario=admin&accion=PAGO_REGISTRADO&desde=2026-09-01&hasta=2026-09-30&pagina=0&tamano=20
 */
@RestController
@RequestMapping("/api/auditoria")
public class AuditoriaController {

    private static final int TAMANO_MAXIMO = 100;

    private final AuditoriaService auditoriaService;

    public AuditoriaController(AuditoriaService auditoriaService) {
        this.auditoriaService = auditoriaService;
    }

    @GetMapping
    public ResponseEntity<?> listar(
            @RequestParam(name = "usuario", required = false) String usuario,
            @RequestParam(name = "accion", required = false) AccionAuditoria accion,
            @RequestParam(name = "desde", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(name = "hasta", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(name = "pagina", defaultValue = "0") int pagina,
            @RequestParam(name = "tamano", defaultValue = "20") int tamano) {

        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            return ResponseEntity.badRequest()
                    .body("La fecha 'desde' no puede ser posterior a 'hasta'.");
        }

        int paginaSegura = Math.max(pagina, 0);
        int tamanoSeguro = Math.min(Math.max(tamano, 1), TAMANO_MAXIMO);

        Page<Auditoria> resultado = auditoriaService.listar(
                usuario, accion, desde, hasta, PageRequest.of(paginaSegura, tamanoSeguro));

        AuditoriaPaginaDTO respuesta = new AuditoriaPaginaDTO(
                resultado.getContent().stream().map(AuditoriaResponseDTO::desde).toList(),
                resultado.getNumber(),
                resultado.getSize(),
                resultado.getTotalElements(),
                resultado.getTotalPages());

        return ResponseEntity.ok(respuesta);
    }
}
package com.microcreditos.microcreditosbackend.controller;

import com.microcreditos.microcreditosbackend.service.MoraService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.NoSuchElementException;

/**
 * Interés moratorio de un crédito. Consulta: los 3 roles. Cobro: los 3 roles. Condonación: solo
 * ADMIN. Los permisos están en SecurityConfig.
 */
@RestController
@RequestMapping("/api/creditos/{creditoId}/mora")
public class MoraController {

    private final MoraService moraService;

    public MoraController(MoraService moraService) {
        this.moraService = moraService;
    }

    public record CobroRequest(BigDecimal monto) {}

    public record CondonacionRequest(BigDecimal monto, String motivo) {}

    @GetMapping
    public ResponseEntity<?> resumen(@PathVariable Long creditoId) {
        try {
            return ResponseEntity.ok(moraService.resumen(creditoId));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }

    @PostMapping("/cobros")
    public ResponseEntity<?> cobrar(@PathVariable Long creditoId, @RequestBody CobroRequest request) {
        try {
            return ResponseEntity.ok(moraService.cobrar(creditoId, request.monto()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/condonaciones")
    public ResponseEntity<?> condonar(@PathVariable Long creditoId, @RequestBody CondonacionRequest request) {
        try {
            return ResponseEntity.ok(moraService.condonar(creditoId, request.monto(), request.motivo()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
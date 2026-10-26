package com.microcreditos.microcreditosbackend.controller;

import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.service.CreditoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/creditos")
public class CreditoController {

    @Autowired
    private CreditoService creditoService;

    @GetMapping
    public List<Credito> listarTodos() {
        return creditoService.listarTodos();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Credito> buscarPorId(@PathVariable Long id) {
        return creditoService.buscarPorId(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/cliente/{clienteId}")
    public List<Credito> buscarPorCliente(@PathVariable Long clienteId) {
        return creditoService.buscarPorCliente(clienteId);
    }

    // DTO simple para recibir los datos del formulario de solicitud de crédito
    public record SolicitudCreditoRequest(
            Long clienteId,
            BigDecimal montoSolicitado,
            BigDecimal tasaInteresMensual,
            Integer plazoMeses
    ) {}

    @PostMapping
    public Credito crear(@RequestBody SolicitudCreditoRequest request) {
        return creditoService.crearCredito(
                request.clienteId(),
                request.montoSolicitado(),
                request.tasaInteresMensual(),
                request.plazoMeses()
        );
    }


    @PutMapping("/{id}/aprobar")
    public ResponseEntity<?> aprobar(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(creditoService.aprobar(id));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{id}/rechazar")
    public ResponseEntity<?> rechazar(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(creditoService.rechazar(id));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // cobradorId = null quita el cobrador asignado.
    public record AsignarCobradorRequest(Long cobradorId) {}

    // Solo ADMIN (ver SecurityConfig).
    @PutMapping("/{id}/cobrador")
    public ResponseEntity<?> asignarCobrador(@PathVariable Long id, @RequestBody AsignarCobradorRequest request) {
        try {
            return ResponseEntity.ok(creditoService.asignarCobrador(id, request.cobradorId()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminar(@PathVariable Long id) {
        try {
            creditoService.eliminar(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
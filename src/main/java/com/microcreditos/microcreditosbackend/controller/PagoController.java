package com.microcreditos.microcreditosbackend.controller;

import com.microcreditos.microcreditosbackend.model.MetodoPago;
import com.microcreditos.microcreditosbackend.model.Pago;
import com.microcreditos.microcreditosbackend.service.PagoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/pagos")
public class PagoController {

    @Autowired
    private PagoService pagoService;

    @GetMapping("/credito/{creditoId}")
    public List<Pago> listarPorCredito(@PathVariable Long creditoId) {
        return pagoService.listarPorCredito(creditoId);
    }

    // metodoPago y referenciaPago son opcionales: sin método se asume EFECTIVO.
    public record RegistroPagoRequest(
        Long creditoId,
        Integer numeroCuota,
        BigDecimal montoPagado,
        MetodoPago metodoPago,
        String referenciaPago
    )   {}

    @PostMapping
    public ResponseEntity<?> registrar(@RequestBody RegistroPagoRequest request) {
        try {
            List<Pago> pagos = pagoService.registrarPago(
                    request.creditoId(),
                    request.numeroCuota(),
                    request.montoPagado(),
                    request.metodoPago(),
                    request.referenciaPago()
            );
            return ResponseEntity.ok(pagos);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    public record ReferenciaExistenteResponse(boolean existe, List<Long> creditos) {}

    // Aviso de posible duplicado: ¿esa referencia ya está en un abono activo? Solo informa, no bloquea.
    // Lo pueden consultar los 3 roles (cae en anyRequest().authenticated()).
    @GetMapping("/referencia-existente")
    public ReferenciaExistenteResponse referenciaExistente(@RequestParam String referencia) {
        List<Long> creditos = pagoService.creditosConReferencia(referencia);
        return new ReferenciaExistenteResponse(!creditos.isEmpty(), creditos);
    }

    public record AnulacionRequest(String motivo) {}

    // Solo ADMIN (ver SecurityConfig). Anula el abono completo al que pertenece
    // el pago indicado y devuelve las filas de anulación creadas.
    @PostMapping("/{id}/anular")
    public ResponseEntity<?> anular(@PathVariable Long id, @RequestBody AnulacionRequest request) {
        try {
            List<Pago> reversos = pagoService.anularAbono(id, request.motivo());
            return ResponseEntity.ok(reversos);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (DataIntegrityViolationException e) {
            // Dos anulaciones simultáneas del mismo pago: la restricción UNIQUE
            // de pago_original_id deja pasar solo una.
            return ResponseEntity.badRequest().body("Ese pago ya fue anulado.");
        }
    }
}
package com.microcreditos.microcreditosbackend.controller;

import com.microcreditos.microcreditosbackend.model.Notificacion;
import com.microcreditos.microcreditosbackend.service.NotificacionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notificaciones")
public class NotificacionController {

    private final NotificacionService notificacionService;

    public NotificacionController(NotificacionService notificacionService) {
        this.notificacionService = notificacionService;
    }

    @GetMapping
    public List<Notificacion> listar() {
        return notificacionService.listarRecientes();
    }

    @GetMapping("/no-leidas/conteo")
    public Map<String, Long> contarNoLeidas() {
        return Map.of("noLeidas", notificacionService.contarNoLeidas());
    }

    @PutMapping("/{id}/leida")
    public ResponseEntity<?> marcarComoLeida(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(notificacionService.marcarComoLeida(id));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/marcar-todas")
    public Map<String, Integer> marcarTodasComoLeidas() {
        return Map.of("marcadas", notificacionService.marcarTodasComoLeidas());
    }
}
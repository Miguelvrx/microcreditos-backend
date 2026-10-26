package com.microcreditos.microcreditosbackend.controller;

import com.microcreditos.microcreditosbackend.dto.UsuarioResponseDTO;
import com.microcreditos.microcreditosbackend.model.RolUsuario;
import com.microcreditos.microcreditosbackend.repository.UsuarioRepository;
import com.microcreditos.microcreditosbackend.service.UsuarioService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/usuarios")
public class UsuarioController {

    @Autowired
    private UsuarioService usuarioService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @GetMapping
    public List<UsuarioResponseDTO> listar() {
        return usuarioService.listarTodos();
    }

    // Solo id y nombre: es lo que necesita el selector de cobrador del crédito.
    public record CobradorDTO(Long id, String nombreUsuario) {}

    // Usuarios con rol COBRADOR que no están desactivados. Solo ADMIN (/api/usuarios/** en SecurityConfig).
    @GetMapping("/cobradores")
    public List<CobradorDTO> listarCobradores() {
        return usuarioRepository.findByRolAndActivoTrue(RolUsuario.COBRADOR).stream()
                .map(u -> new CobradorDTO(u.getId(), u.getNombreUsuario()))
                .toList();
    }

    @PutMapping("/{id}/desactivar")
    public ResponseEntity<?> desactivar(@PathVariable Long id, Authentication authentication) {
        try {
            return ResponseEntity.ok(usuarioService.desactivar(id, authentication.getName()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{id}/activar")
    public ResponseEntity<?> activar(@PathVariable Long id) {
        return ResponseEntity.ok(usuarioService.activar(id));
    }
}
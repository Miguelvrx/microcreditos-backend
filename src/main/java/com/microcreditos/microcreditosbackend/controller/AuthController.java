package com.microcreditos.microcreditosbackend.controller;

import com.microcreditos.microcreditosbackend.dto.LoginRequest;
import com.microcreditos.microcreditosbackend.dto.LoginResponse;
import com.microcreditos.microcreditosbackend.dto.RegistroRequest;
import com.microcreditos.microcreditosbackend.model.AccionAuditoria;
import com.microcreditos.microcreditosbackend.model.Usuario;
import com.microcreditos.microcreditosbackend.repository.UsuarioRepository;
import com.microcreditos.microcreditosbackend.security.JwtService;
import com.microcreditos.microcreditosbackend.service.AuditoriaService;
import com.microcreditos.microcreditosbackend.service.UsuarioService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UsuarioService usuarioService;
    private final AuditoriaService auditoriaService;

    public AuthController(UsuarioRepository usuarioRepository,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService,
                           UsuarioService usuarioService,
                           AuditoriaService auditoriaService) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.usuarioService = usuarioService;
        this.auditoriaService = auditoriaService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Usuario usuario = usuarioRepository.findByNombreUsuario(request.nombreUsuario())
                .orElse(null);

        if (usuario == null || !passwordEncoder.matches(request.password(), usuario.getPassword())) {
            auditoriaService.registrar(
                    AccionAuditoria.LOGIN_FALLIDO,
                    "Usuario",
                    usuario != null ? usuario.getId() : null,
                    "Login fallido para '" + request.nombreUsuario() + "': credenciales incorrectas");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Usuario o contraseña incorrectos");
        }

        if (!usuario.isActivo()) {
            auditoriaService.registrar(
                    AccionAuditoria.LOGIN_FALLIDO,
                    "Usuario",
                    usuario.getId(),
                    "Login fallido para '" + request.nombreUsuario() + "': usuario desactivado");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Tu usuario fue desactivado. Contacta a un administrador.");
        }

        String token = jwtService.generarToken(usuario.getNombreUsuario(), usuario.getRol().name());

        auditoriaService.registrar(
                AccionAuditoria.LOGIN_EXITOSO,
                "Usuario",
                usuario.getId(),
                "Login exitoso: " + usuario.getNombreUsuario());

        return ResponseEntity.ok(new LoginResponse(token, usuario.getNombreUsuario(), usuario.getRol().name()));
    }

    // Protegido: solo un usuario autenticado con rol ADMIN puede crear
    // usuarios nuevos (ver SecurityConfig.java, regla
    // "/api/auth/registro").hasRole("ADMIN")). Antes de este cambio era
    // público y cualquiera podía crear cuentas sin autenticarse.
    // La creación y su registro de auditoría viven en UsuarioService.crear()
    // para que ocurran en una sola transacción.
    @PostMapping("/registro")
    public ResponseEntity<?> registro(@Valid @RequestBody RegistroRequest request) {
        if (usuarioRepository.findByNombreUsuario(request.nombreUsuario()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Ya existe un usuario con ese nombre");
        }

        Usuario usuario = usuarioService.crear(
                request.nombreUsuario(),
                request.password(),
                request.rol());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body("Usuario creado: " + usuario.getNombreUsuario());
    }
}
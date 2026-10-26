package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.dto.UsuarioResponseDTO;
import com.microcreditos.microcreditosbackend.model.AccionAuditoria;
import com.microcreditos.microcreditosbackend.model.Usuario;
import com.microcreditos.microcreditosbackend.repository.UsuarioRepository;
import com.microcreditos.microcreditosbackend.model.RolUsuario;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UsuarioService {

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private AuditoriaService auditoriaService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    public List<UsuarioResponseDTO> listarTodos() {
        return usuarioRepository.findAll().stream()
                .map(u -> new UsuarioResponseDTO(u.getId(), u.getNombreUsuario(), u.getRol().name(), u.isActivo()))
                .toList();
    }

    /**
     * Crea un usuario nuevo (contraseña hasheada) y audita la creación.
     * La validación de nombre duplicado sigue en AuthController.
     * Nunca se incluye la contraseña en el detalle de auditoría.
     */
    @Transactional
    public Usuario crear(String nombreUsuario, String passwordPlano, RolUsuario rol) {
        Usuario usuario = new Usuario();
        usuario.setNombreUsuario(nombreUsuario);
        usuario.setPassword(passwordEncoder.encode(passwordPlano));
        usuario.setRol(rol);
        Usuario guardado = usuarioRepository.save(usuario);

        auditoriaService.registrar(
                AccionAuditoria.USUARIO_CREADO,
                "Usuario",
                guardado.getId(),
                "Usuario creado: " + guardado.getNombreUsuario() + " con rol " + guardado.getRol());

        return guardado;
    }

    @Transactional
    public UsuarioResponseDTO desactivar(Long id, String nombreUsuarioActual) {
        Usuario usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado con id: " + id));

        if (usuario.getNombreUsuario().equals(nombreUsuarioActual)) {
            throw new IllegalStateException("No puedes desactivar tu propio usuario.");
        }

       if (usuario.getRol() == RolUsuario.ADMIN && usuario.isActivo()) {
            long adminsActivos = usuarioRepository.findAll().stream()
            .filter(u -> u.getRol() == RolUsuario.ADMIN && u.isActivo())
            .count();
            if (adminsActivos <= 1) {
                throw new IllegalStateException("No puedes desactivar al último usuario ADMIN activo.");
            }
        }

        boolean estabaActivo = usuario.isActivo();
        usuario.setActivo(false);
        usuarioRepository.save(usuario);

        // Solo se audita si hubo un cambio real de estado.
        if (estabaActivo) {
            auditoriaService.registrar(
                    AccionAuditoria.USUARIO_DESACTIVADO,
                    "Usuario",
                    usuario.getId(),
                    "Usuario desactivado: " + usuario.getNombreUsuario());
        }

       return new UsuarioResponseDTO(usuario.getId(), usuario.getNombreUsuario(), usuario.getRol().name(), usuario.isActivo());

    }

    @Transactional
    public UsuarioResponseDTO activar(Long id) {
        Usuario usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado con id: " + id));

        boolean estabaInactivo = !usuario.isActivo();
        usuario.setActivo(true);
        usuarioRepository.save(usuario);

        // Solo se audita si hubo un cambio real de estado.
        if (estabaInactivo) {
            auditoriaService.registrar(
                    AccionAuditoria.USUARIO_ACTIVADO,
                    "Usuario",
                    usuario.getId(),
                    "Usuario activado: " + usuario.getNombreUsuario());
        }

        return new UsuarioResponseDTO(usuario.getId(), usuario.getNombreUsuario(), usuario.getRol().name(), usuario.isActivo());
    }
}
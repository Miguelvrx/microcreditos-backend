package com.microcreditos.microcreditosbackend.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "usuarios")
@Data
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String nombreUsuario;

    // Se guarda SIEMPRE hasheada con BCrypt, nunca en texto plano.
    @Column(nullable = false)
    private String password;

    // Rol simple por ahora (ej. "ADMIN"). Se puede ampliar a una entidad
    // Rol aparte más adelante si se necesitan permisos más finos.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RolUsuario rol;
    
    // Nuevo: permite desactivar el acceso de un usuario sin borrar su
    // registro (conserva el historial). Un usuario con activo=false no
    // puede iniciar sesión (ver AuthController.login()).
    @Column(nullable = false)
    private boolean activo = true;
}
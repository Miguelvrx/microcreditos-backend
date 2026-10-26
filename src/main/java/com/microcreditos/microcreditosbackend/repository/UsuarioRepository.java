package com.microcreditos.microcreditosbackend.repository;

import com.microcreditos.microcreditosbackend.model.RolUsuario;
import com.microcreditos.microcreditosbackend.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByNombreUsuario(String nombreUsuario);

    // Para el selector de cobrador: solo usuarios con ese rol que no estén desactivados.
    List<Usuario> findByRolAndActivoTrue(RolUsuario rol);
}
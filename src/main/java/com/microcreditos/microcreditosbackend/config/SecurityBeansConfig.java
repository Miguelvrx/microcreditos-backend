package com.microcreditos.microcreditosbackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

// Configuración mínima para el paso 3 (login).
// La configuración completa de SecurityFilterChain (rutas públicas/privadas,
// filtro JWT, etc.) se agrega en los pasos 4 y 5.
@Configuration
public class SecurityBeansConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
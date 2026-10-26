package com.microcreditos.microcreditosbackend.config;

import com.microcreditos.microcreditosbackend.security.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // API sin estado autenticada por JWT: no usamos cookies/sesión de
                // Spring, así que CSRF (pensado para ataques basados en cookies)
                // no aplica aquí. Spring Security 7 lo protege por defecto en
                // endpoints API, así que hay que desactivarlo explícitamente.
                .csrf(AbstractHttpConfigurer::disable)

                // Habilita CORS usando el bean corsConfigurationSource() de abajo.
                // Sin esto, el navegador bloquea las peticiones del frontend
                // (localhost:5173) al backend (localhost:8080) por ser orígenes
                // distintos, aunque el backend esté funcionando perfectamente.
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // Sin sesión guardada en el servidor: cada request se autentica
                // solo con el JWT que llega en el header Authorization.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Sin token, token expirado o inválido -> 401 (no autenticado).
                // Con sesión válida pero sin el rol requerido sigue siendo 403 (lo maneja
                // el AccessDeniedHandler por defecto).
                .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/api/auth/registro").hasRole("ADMIN")
                        .requestMatchers("/api/usuarios/**").hasRole("ADMIN")
                        .requestMatchers("/api/auditoria/**").hasRole("ADMIN")
                         // Notificaciones: los 3 roles pueden verlas y marcarlas como leídas
                        .requestMatchers("/api/notificaciones/**").hasAnyRole("ADMIN", "OPERADOR", "COBRADOR")

                        // Crear cliente / crédito: ADMIN y OPERADOR, nunca COBRADOR
                        .requestMatchers(HttpMethod.POST, "/api/clientes").hasAnyRole("ADMIN", "OPERADOR")
                        .requestMatchers(HttpMethod.PUT, "/api/clientes/*").hasAnyRole("ADMIN", "OPERADOR")
                        .requestMatchers(HttpMethod.POST, "/api/creditos").hasAnyRole("ADMIN", "OPERADOR")

                        // Registrar pago: los 3 roles pueden (incluye COBRADOR)
                        .requestMatchers(HttpMethod.POST, "/api/pagos").hasAnyRole("ADMIN", "OPERADOR", "COBRADOR")

                        // Ya existentes: solo ADMIN
                        .requestMatchers(HttpMethod.PUT, "/api/creditos/*/aprobar").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/creditos/*/rechazar").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/creditos/*").hasRole("ADMIN")

                        // Nuevo: eliminar cliente también queda solo ADMIN (ver punto 2 abajo)
                        .requestMatchers(HttpMethod.DELETE, "/api/clientes/*").hasRole("ADMIN")

                        // Anular un abono: solo ADMIN
                        .requestMatchers(HttpMethod.POST, "/api/pagos/*/anular").hasRole("ADMIN")

                        // Interés moratorio: cobrar, los 3 roles; condonar, solo ADMIN
                        .requestMatchers(HttpMethod.POST, "/api/creditos/*/mora/cobros").hasAnyRole("ADMIN", "OPERADOR", "COBRADOR")
                        .requestMatchers(HttpMethod.POST, "/api/creditos/*/mora/condonaciones").hasRole("ADMIN")

                                            

                        .anyRequest().authenticated()
                )

                // Nuestro filtro corre antes del filtro estándar de
                // usuario/contraseña, para que el token ya deje al usuario
                // autenticado cuando Spring evalúa las reglas de arriba.
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setExposedHeaders(List.of("Authorization"));   // <-- agrega esto
        config.setAllowCredentials(true);                      // <-- y esto
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
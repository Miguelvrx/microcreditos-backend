package com.microcreditos.microcreditosbackend.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica los códigos de respuesta de la seguridad:
 *  - sin token o con token inválido -> 401 (no autenticado)
 *  - autenticado pero sin el rol requerido -> 403 (sin permiso)
 *
 * Levanta la aplicación completa, así que usa la base de pruebas (la URL se fuerza a
 * microcreditos_test y se verifica, igual que en AnulacionIntegracionTest).
 * No escribe datos: todas las peticiones son rechazadas por la seguridad antes de
 * llegar a un controlador.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SeguridadRespuestasTest {

    private static final String URL_BASE_DE_PRUEBAS = "jdbc:postgresql://localhost:5432/microcreditos_test";

    @DynamicPropertySource
    static void apuntarABaseDePruebas(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL_BASE_DE_PRUEBAS);
        registry.add("mora.actualizar-al-iniciar", () -> "false");
    }

    @Value("${spring.datasource.url}")
    private String urlEfectiva;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void verificarBase() {
        assertThat(urlEfectiva).endsWith("_test");
    }

    @Test
    void sinToken_respondeUnauthorized() throws Exception {
        mockMvc.perform(get("/api/clientes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void conTokenInvalido_respondeUnauthorized() throws Exception {
        mockMvc.perform(get("/api/clientes").header("Authorization", "Bearer esto-no-es-un-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void conEncabezadoSinBearer_respondeUnauthorized() throws Exception {
        mockMvc.perform(get("/api/clientes").header("Authorization", "Basic abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "operador", roles = "OPERADOR")
    void operadorSinPermisoParaAnular_respondeForbidden() throws Exception {
        mockMvc.perform(post("/api/pagos/1/anular")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"prueba\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "cobrador", roles = "COBRADOR")
    void cobradorSinPermisoParaVerAuditoria_respondeForbidden() throws Exception {
        mockMvc.perform(get("/api/auditoria"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anularSinToken_respondeUnauthorizedYNoForbidden() throws Exception {
        mockMvc.perform(post("/api/pagos/1/anular")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"prueba\"}"))
                .andExpect(status().isUnauthorized());
    }
}
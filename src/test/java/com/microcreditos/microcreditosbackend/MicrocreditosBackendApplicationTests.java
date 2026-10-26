package com.microcreditos.microcreditosbackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprueba que la aplicación arranca (contexto de Spring, Flyway y validación del esquema de Hibernate).
 *
 * Importante: corre SIEMPRE contra microcreditos_test. Si usara la base de desarrollo, Flyway podría
 * aplicar una migración pendiente a la base real al correr los tests. Misma protección que
 * MoraIntegracionTest: la URL se fuerza a la base de pruebas y se verifica que termine en _test.
 */
@SpringBootTest
class MicrocreditosBackendApplicationTests {

    private static final String URL_BASE_DE_PRUEBAS = "jdbc:postgresql://localhost:5432/microcreditos_test";

    @DynamicPropertySource
    static void apuntarABaseDePruebas(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL_BASE_DE_PRUEBAS);
        registry.add("mora.actualizar-al-iniciar", () -> "false");
    }

    @Value("${spring.datasource.url}")
    private String urlEfectiva;

    @Test
    void contextLoads() {
        assertThat(urlEfectiva)
                .as("Solo puede correr contra una base terminada en _test")
                .endsWith("_test");
    }
}
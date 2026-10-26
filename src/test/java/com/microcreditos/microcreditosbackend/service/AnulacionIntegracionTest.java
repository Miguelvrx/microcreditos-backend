package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.Cliente;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.Pago;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueba de integración con PostgreSQL real (base microcreditos_test, esquema
 * creado por Flyway: V1 + V2). Confirma lo que los mocks no pueden: que el
 * derrame, la anulación y los cambios de estado funcionan con JPA y la base.
 *
 * SEGURIDAD: la URL se fuerza a microcreditos_test (gana sobre cualquier variable
 * de entorno como SPRING_DATASOURCE_URL) y, además, la prueba falla si la URL
 * efectiva no termina en "_test". Cada prueba hace rollback al terminar.
 * El usuario y la contraseña se heredan de la configuración normal de la app.
 */
@SpringBootTest
@Transactional
@WithMockUser(username = "admin", roles = "ADMIN")
class AnulacionIntegracionTest {

    // Cambia el puerto aquí si tu PostgreSQL no usa el 5432.
    private static final String URL_BASE_DE_PRUEBAS = "jdbc:postgresql://localhost:5432/microcreditos_test";

    @DynamicPropertySource
    static void apuntarABaseDePruebas(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL_BASE_DE_PRUEBAS);
        registry.add("mora.actualizar-al-iniciar", () -> "false");
    }

    @Value("${spring.datasource.url}")
    private String urlEfectiva;

    @Autowired private PagoService pagoService;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;

    private final LocalDate hoy = LocalDate.now();
    private long ultimaAuditoriaAntes;

    @BeforeEach
    void verificarBaseYMarcarAuditoria() {
        assertThat(urlEfectiva)
                .as("Esta prueba solo puede correr contra una base terminada en _test")
                .endsWith("_test");
        ultimaAuditoriaAntes = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM auditoria", Long.class);
    }

    /**
     * Crea un cliente y un crédito de $1000 a 3 meses (2.5%, cuota 350.14) iniciado hace
     * 2 meses y 5 días: las cuotas 1 y 2 ya vencieron y la 3 no. Con conPagosPrevios deja
     * la cuota 1 completa y la 2 con 100.00 (el mismo punto de partida del crédito #15).
     * Termina con flush + clear para que el servicio cargue todo fresco, como en una petición real.
     */
    private Long crearCredito(Credito.EstadoCredito estado, boolean conPagosPrevios) {
        Cliente cliente = new Cliente();
        cliente.setNombreCompleto("Cliente Integracion");
        cliente.setNumeroDocumento("T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        em.persist(cliente);

        Credito credito = new Credito();
        credito.setCliente(cliente);
        credito.setMontoSolicitado(new BigDecimal("1000.00"));
        credito.setTasaInteresMensual(new BigDecimal("2.50"));
        credito.setPlazoMeses(3);
        credito.setCuotaMensual(new BigDecimal("350.14"));
        credito.setFechaInicio(hoy.minusMonths(2).minusDays(5));
        credito.setEstado(estado);
        em.persist(credito);

        if (conPagosPrevios) {
            em.persist(pagoPrevio(credito, 1, "350.14"));
            em.persist(pagoPrevio(credito, 2, "100.00"));
        }

        em.flush();
        em.clear();
        return credito.getId();
    }

    private Pago pagoPrevio(Credito credito, int cuota, String monto) {
        Pago p = new Pago();
        p.setCredito(credito);
        p.setNumeroCuota(cuota);
        p.setMontoPagado(new BigDecimal(monto));
        p.setFechaPago(hoy);
        p.setFechaVencimiento(credito.getFechaInicio().plusMonths(cuota));
        p.setEstado(Pago.EstadoPago.PAGADO);
        return p;
    }

    private List<String> auditoriaNueva() {
        return jdbc.queryForList("SELECT accion FROM auditoria WHERE id > ? ORDER BY id",
                String.class, ultimaAuditoriaAntes);
    }

    private BigDecimal totalPagado(Long creditoId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(monto_pagado), 0) FROM pagos WHERE credito_id = ?",
                BigDecimal.class, creditoId);
    }

    private Credito.EstadoCredito estadoEnBase(Long creditoId) {
        em.flush();
        em.clear();
        return em.find(Credito.class, creditoId).getEstado();
    }

    @Test
    void pagarSaldarYAnular_dejaElCreditoEnMoraConAuditoriaYNotificacion() {
        Long creditoId = crearCredito(Credito.EstadoCredito.APROBADO, true);

        List<Pago> abono = pagoService.registrarPago(creditoId, 2, new BigDecimal("600.28"));

        assertThat(abono).hasSize(2);
        assertThat(estadoEnBase(creditoId)).isEqualTo(Credito.EstadoCredito.PAGADO);
        assertThat(totalPagado(creditoId)).isEqualByComparingTo("1050.42");

        List<Pago> reversos = pagoService.anularAbono(abono.get(0).getId(), "Prueba de integración");

        assertThat(reversos).hasSize(2);
        assertThat(estadoEnBase(creditoId)).isEqualTo(Credito.EstadoCredito.EN_MORA);
        assertThat(totalPagado(creditoId)).isEqualByComparingTo("450.14");

        assertThat(auditoriaNueva()).containsExactly(
                "PAGO_REGISTRADO", "PAGO_REGISTRADO", "PAGO_REGISTRADO",
                "CREDITO_PAGADO",
                "PAGO_ANULADO", "CREDITO_REABIERTO", "CREDITO_EN_MORA");

        Integer notificaciones = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notificaciones WHERE credito_id = ? AND tipo = 'CREDITO_EN_MORA'",
                Integer.class, creditoId);
        assertThat(notificaciones).isEqualTo(1);
    }

    @Test
    void creditoEnMoraQueSePoneAlDia_vuelveAAprobado() {
        // Cuotas 1 y 2 vencidas y sin pagar; un solo abono cubre ambas.
        Long creditoId = crearCredito(Credito.EstadoCredito.EN_MORA, false);

        List<Pago> abono = pagoService.registrarPago(creditoId, 1, new BigDecimal("700.28"));

        assertThat(abono).hasSize(2);
        assertThat(estadoEnBase(creditoId)).isEqualTo(Credito.EstadoCredito.APROBADO);
        assertThat(auditoriaNueva()).containsExactly(
                "PAGO_REGISTRADO", "PAGO_REGISTRADO", "PAGO_REGISTRADO", "CREDITO_AL_DIA");
    }
}
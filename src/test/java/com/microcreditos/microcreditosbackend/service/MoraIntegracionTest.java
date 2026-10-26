package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.Cliente;
import com.microcreditos.microcreditosbackend.model.Credito;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mora contra PostgreSQL real (base microcreditos_test, esquema V1 + V2 + V3). Confirma lo que los
 * mocks no pueden: que MoraService escribe en movimientos_mora, que el CHECK de auditoria acepta
 * MORA_COBRADA y MORA_CONDONADA, y que los CHECK de la tabla nueva se cumplen en la base.
 *
 * Misma seguridad que AnulacionIntegracionTest: la URL se fuerza a microcreditos_test, se verifica
 * que termine en _test y cada prueba hace rollback.
 */
@SpringBootTest
@Transactional
@WithMockUser(username = "admin", roles = "ADMIN")
class MoraIntegracionTest {

    private static final String URL_BASE_DE_PRUEBAS = "jdbc:postgresql://localhost:5432/microcreditos_test";

    @DynamicPropertySource
    static void apuntarABaseDePruebas(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL_BASE_DE_PRUEBAS);
        registry.add("mora.actualizar-al-iniciar", () -> "false");
    }

    @Value("${spring.datasource.url}")
    private String urlEfectiva;

    @Autowired private MoraService moraService;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;

    private long ultimaAuditoriaAntes;

    @BeforeEach
    void verificarBaseYMarcarAuditoria() {
        assertThat(urlEfectiva).as("Solo puede correr contra una base terminada en _test").endsWith("_test");
        ultimaAuditoriaAntes = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM auditoria", Long.class);
    }

    /** Crédito APROBADO sin pagos, iniciado hace 2 meses y 5 días: sus cuotas 1 y 2 ya vencieron, así que hay mora. */
    private Long crearCreditoConMora() {
        Cliente cliente = new Cliente();
        cliente.setNombreCompleto("Cliente Mora");
        cliente.setNumeroDocumento("M" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        em.persist(cliente);

        Credito credito = new Credito();
        credito.setCliente(cliente);
        credito.setMontoSolicitado(new BigDecimal("1000.00"));
        credito.setTasaInteresMensual(new BigDecimal("2.50"));
        credito.setPlazoMeses(3);
        credito.setCuotaMensual(new BigDecimal("350.14"));
        credito.setFechaInicio(LocalDate.now().minusMonths(2).minusDays(5));
        credito.setEstado(Credito.EstadoCredito.APROBADO);
        em.persist(credito);

        em.flush();
        em.clear();
        return credito.getId();
    }

    @Test
    void cobrarYCondonar_guardanLosMovimientosYLaAuditoria() {
        Long creditoId = crearCreditoConMora();

        BigDecimal devengado = moraService.resumen(creditoId).devengado();
        assertThat(devengado).isGreaterThan(new BigDecimal("1.50"));   // hay mora de sobra para el ensayo

        moraService.cobrar(creditoId, new BigDecimal("1.00"));
        MoraService.ResumenMora resumen = moraService.condonar(creditoId, new BigDecimal("0.50"), "Prueba de integración");

        assertThat(resumen.cobrado()).isEqualByComparingTo("1.00");
        assertThat(resumen.condonado()).isEqualByComparingTo("0.50");
        assertThat(resumen.saldo()).isEqualByComparingTo(devengado.subtract(new BigDecimal("1.50")));

        List<Map<String, Object>> filas = jdbc.queryForList(
                "SELECT tipo, monto, usuario, motivo FROM movimientos_mora WHERE credito_id = ? ORDER BY id", creditoId);
        assertThat(filas).hasSize(2);
        assertThat(filas.get(0).get("tipo")).isEqualTo("COBRO");
        assertThat((BigDecimal) filas.get(0).get("monto")).isEqualByComparingTo("1.00");
        assertThat(filas.get(0).get("usuario")).isEqualTo("admin");
        assertThat(filas.get(1).get("tipo")).isEqualTo("CONDONACION");
        assertThat(filas.get(1).get("motivo")).isEqualTo("Prueba de integración");

        // Si el CHECK de auditoria no incluyera las acciones nuevas, la inserción habría fallado.
        List<String> acciones = jdbc.queryForList(
                "SELECT accion FROM auditoria WHERE id > ? ORDER BY id", String.class, ultimaAuditoriaAntes);
        assertThat(acciones).containsExactly("MORA_COBRADA", "MORA_CONDONADA");
    }

    @Test
    void cobrarMasQueElSaldo_sePideRechazadoYNoDejaFila() {
        Long creditoId = crearCreditoConMora();
        BigDecimal saldo = moraService.resumen(creditoId).saldo();

        assertThatThrownBy(() -> moraService.cobrar(creditoId, saldo.add(new BigDecimal("0.01"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("supera el saldo de mora");

        Integer filas = jdbc.queryForObject(
                "SELECT COUNT(*) FROM movimientos_mora WHERE credito_id = ?", Integer.class, creditoId);
        assertThat(filas).isZero();
    }

    @Test
    void laBaseRechazaUnaCondonacionSinMotivo() {
        Long creditoId = crearCreditoConMora();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO movimientos_mora (credito_id, tipo, monto, fecha, motivo, usuario, creado_en) "
                        + "VALUES (?, 'CONDONACION', 1.00, CURRENT_DATE, NULL, 'admin', now())", creditoId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void laBaseRechazaUnMontoEnCero() {
        Long creditoId = crearCreditoConMora();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO movimientos_mora (credito_id, tipo, monto, fecha, motivo, usuario, creado_en) "
                        + "VALUES (?, 'COBRO', 0, CURRENT_DATE, NULL, 'admin', now())", creditoId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
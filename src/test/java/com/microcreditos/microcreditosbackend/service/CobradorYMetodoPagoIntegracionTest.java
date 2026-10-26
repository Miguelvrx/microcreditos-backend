package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.Cliente;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.MetodoPago;
import com.microcreditos.microcreditosbackend.model.Pago;
import com.microcreditos.microcreditosbackend.model.RolUsuario;
import com.microcreditos.microcreditosbackend.model.Usuario;
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
 * Integración con PostgreSQL real (microcreditos_test, esquema creado por Flyway: V1 a V4). Confirma que la
 * migración V4 y las entidades coinciden: método y referencia se guardan, la restricción de la base rechaza una
 * transferencia sin referencia, y el cobrador se asigna y queda en la auditoría.
 *
 * Mismas protecciones que AnulacionIntegracionTest: la URL se fuerza a una base terminada en "_test",
 * se apaga la revisión de mora al arrancar (regla del punto 43) y cada prueba hace rollback.
 */
@SpringBootTest
@Transactional
@WithMockUser(username = "admin", roles = "ADMIN")
class CobradorYMetodoPagoIntegracionTest {

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
    @Autowired private CreditoService creditoService;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;

    private long ultimaAuditoriaAntes;

    @BeforeEach
    void verificarBaseYMarcarAuditoria() {
        assertThat(urlEfectiva)
                .as("Esta prueba solo puede correr contra una base terminada en _test")
                .endsWith("_test");
        ultimaAuditoriaAntes = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM auditoria", Long.class);
    }

    /** Crédito APROBADO de $1000 a 3 meses iniciado hoy (ninguna cuota vencida). */
    private Long crearCreditoAprobado() {
        Cliente cliente = new Cliente();
        cliente.setNombreCompleto("Cliente Integracion V4");
        cliente.setNumeroDocumento("T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        em.persist(cliente);

        Credito credito = new Credito();
        credito.setCliente(cliente);
        credito.setMontoSolicitado(new BigDecimal("1000.00"));
        credito.setTasaInteresMensual(new BigDecimal("2.50"));
        credito.setPlazoMeses(3);
        credito.setCuotaMensual(new BigDecimal("350.14"));
        credito.setFechaInicio(LocalDate.now());
        credito.setEstado(Credito.EstadoCredito.APROBADO);
        em.persist(credito);

        em.flush();
        em.clear();
        return credito.getId();
    }

    private Usuario crearCobrador() {
        Usuario u = new Usuario();
        u.setNombreUsuario("cobrador_" + UUID.randomUUID().toString().substring(0, 8));
        u.setPassword("no-es-un-hash-real");
        u.setRol(RolUsuario.COBRADOR);
        u.setActivo(true);
        em.persist(u);

        em.flush();
        em.clear();
        return u;
    }

    private List<String> auditoriaNueva() {
        return jdbc.queryForList("SELECT accion FROM auditoria WHERE id > ? ORDER BY id",
                String.class, ultimaAuditoriaAntes);
    }

    @Test
    void transferenciaConReferencia_seGuardaEnLaBaseLimpia() {
        Long creditoId = crearCreditoAprobado();

        List<Pago> abono = pagoService.registrarPago(creditoId, 1, new BigDecimal("100.00"),
                MetodoPago.TRANSFERENCIA, " clave-77 ");
        em.flush();
        em.clear();

        Map<String, Object> fila = jdbc.queryForMap(
                "SELECT metodo_pago, referencia_pago FROM pagos WHERE id = ?", abono.get(0).getId());
        assertThat(fila.get("metodo_pago")).isEqualTo("TRANSFERENCIA");
        assertThat(fila.get("referencia_pago")).isEqualTo("CLAVE-77");
    }

    @Test
    void laBaseRechazaUnaTransferenciaSinReferencia() {
        Long creditoId = crearCreditoAprobado();
        List<Pago> abono = pagoService.registrarPago(creditoId, 1, new BigDecimal("100.00"));   // efectivo
        em.flush();

        // Aunque el servicio ya lo valida, la restricción de V4 es la última barrera
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE pagos SET metodo_pago = 'TRANSFERENCIA', referencia_pago = NULL WHERE id = ?",
                abono.get(0).getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void asignarCobrador_quedaEnLaBaseYEnLaAuditoria() {
        Long creditoId = crearCreditoAprobado();
        Usuario cobrador = crearCobrador();

        creditoService.asignarCobrador(creditoId, cobrador.getId());
        em.flush();
        em.clear();

        Long enBase = jdbc.queryForObject("SELECT cobrador_id FROM creditos WHERE id = ?", Long.class, creditoId);
        assertThat(enBase).isEqualTo(cobrador.getId());
        assertThat(auditoriaNueva()).containsExactly("COBRADOR_ASIGNADO");
    }
}
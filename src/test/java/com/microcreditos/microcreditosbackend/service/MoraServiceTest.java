package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.AccionAuditoria;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.MovimientoMora;
import com.microcreditos.microcreditosbackend.model.Pago;
import com.microcreditos.microcreditosbackend.model.TipoMovimientoMora;
import com.microcreditos.microcreditosbackend.repository.CreditoRepository;
import com.microcreditos.microcreditosbackend.repository.MovimientoMoraRepository;
import com.microcreditos.microcreditosbackend.repository.PagoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Saldo de mora, cobros y condonaciones. Crédito de referencia: $1000 a 3 meses, cuota 350.14,
 * inicio 01/08/2026. Hoy = 11/09/2026: la cuota 1 lleva 10 días vencida; con 5% mensual y 3 días
 * de gracia el interés devengado es de 7 días = 4.08.
 */
@ExtendWith(MockitoExtension.class)
class MoraServiceTest {

    private static final long CREDITO_ID = 15L;
    private static final LocalDate INICIO = LocalDate.of(2026, 8, 1);
    private static final LocalDate HOY = LocalDate.of(2026, 9, 11);

    @Mock private CreditoRepository creditoRepository;
    @Mock private PagoRepository pagoRepository;
    @Mock private MovimientoMoraRepository movimientoMoraRepository;
    @Mock private AuditoriaService auditoriaService;

    private MoraService service;
    private Credito credito;
    private final List<Pago> pagos = new ArrayList<>();
    private final List<MovimientoMora> movimientos = new ArrayList<>();
    private final AtomicLong secuencia = new AtomicLong(0);

    @BeforeEach
    void preparar() {
        service = new MoraService(creditoRepository, pagoRepository, movimientoMoraRepository,
                new InteresMoratorioService(new BigDecimal("5"), 3), auditoriaService);

        credito = new Credito();
        credito.setId(CREDITO_ID);
        credito.setMontoSolicitado(new BigDecimal("1000.00"));
        credito.setTasaInteresMensual(new BigDecimal("2.5"));
        credito.setPlazoMeses(3);
        credito.setCuotaMensual(new BigDecimal("350.14"));
        credito.setFechaInicio(INICIO);
        credito.setEstado(Credito.EstadoCredito.EN_MORA);

        lenient().when(creditoRepository.findById(CREDITO_ID)).thenReturn(Optional.of(credito));
        lenient().when(pagoRepository.findByCreditoId(CREDITO_ID)).thenAnswer(inv -> new ArrayList<>(pagos));
        lenient().when(movimientoMoraRepository.findByCreditoIdOrderByIdDesc(CREDITO_ID)).thenAnswer(inv -> {
            List<MovimientoMora> copia = new ArrayList<>(movimientos);
            Collections.reverse(copia);
            return copia;
        });
        lenient().when(movimientoMoraRepository.save(any(MovimientoMora.class))).thenAnswer(inv -> {
            MovimientoMora m = inv.getArgument(0);
            m.setId(secuencia.incrementAndGet());
            movimientos.add(m);
            return m;
        });

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private void agregarMovimiento(TipoMovimientoMora tipo, String monto) {
        MovimientoMora m = new MovimientoMora();
        m.setId(secuencia.incrementAndGet());
        m.setCreditoId(CREDITO_ID);
        m.setTipo(tipo);
        m.setMonto(new BigDecimal(monto));
        m.setFecha(HOY);
        m.setUsuario("admin");
        movimientos.add(m);
    }

    // ------------------------------------------------------------ resumen

    @Test
    void resumenSinMovimientos_elSaldoEsLoDevengado() {
        MoraService.ResumenMora resumen = service.resumen(CREDITO_ID, HOY);

        assertThat(resumen.devengado()).isEqualByComparingTo("4.08");
        assertThat(resumen.cobrado()).isEqualByComparingTo("0.00");
        assertThat(resumen.condonado()).isEqualByComparingTo("0.00");
        assertThat(resumen.saldo()).isEqualByComparingTo("4.08");
        assertThat(resumen.cuotas()).hasSize(1);
        assertThat(resumen.movimientos()).isEmpty();
    }

    @Test
    void resumenDeUnCreditoInexistente_seReportaComoNoEncontrado() {
        assertThatThrownBy(() -> service.resumen(999L, HOY))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void elSaldoNuncaEsNegativo() {
        // Si la tasa bajó después de cobrar, lo cobrado puede superar lo devengado hoy.
        agregarMovimiento(TipoMovimientoMora.COBRO, "10.00");

        assertThat(service.resumen(CREDITO_ID, HOY).saldo()).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------ cobro

    @Test
    void cobroParcial_bajaElSaldoYDejaRastro() {
        MoraService.ResumenMora resumen = service.cobrar(CREDITO_ID, new BigDecimal("2.00"), HOY);

        assertThat(resumen.cobrado()).isEqualByComparingTo("2.00");
        assertThat(resumen.saldo()).isEqualByComparingTo("2.08");

        assertThat(movimientos).hasSize(1);
        MovimientoMora m = movimientos.get(0);
        assertThat(m.getTipo()).isEqualTo(TipoMovimientoMora.COBRO);
        assertThat(m.getMonto()).isEqualByComparingTo("2.00");
        assertThat(m.getFecha()).isEqualTo(HOY);
        assertThat(m.getUsuario()).isEqualTo("admin");
        assertThat(m.getCreditoId()).isEqualTo(CREDITO_ID);
        verify(auditoriaService).registrar(eq(AccionAuditoria.MORA_COBRADA), eq("Credito"), eq(CREDITO_ID), anyString());
    }

    @Test
    void cobrarTodoElSaldo_loDejaEnCeroYNoSePuedeCobrarMas() {
        MoraService.ResumenMora resumen = service.cobrar(CREDITO_ID, new BigDecimal("4.08"), HOY);

        assertThat(resumen.saldo()).isEqualByComparingTo("0.00");
        assertThatThrownBy(() -> service.cobrar(CREDITO_ID, new BigDecimal("0.01"), HOY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("supera el saldo de mora");
    }

    @Test
    void noSePuedeCobrarMasQueElSaldo() {
        assertThatThrownBy(() -> service.cobrar(CREDITO_ID, new BigDecimal("4.09"), HOY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("supera el saldo de mora");
        assertThat(movimientos).isEmpty();
        verify(auditoriaService, never()).registrar(any(), anyString(), anyLong(), anyString());
    }

    @Test
    void elMontoDebeSerMayorACero() {
        assertThatThrownBy(() -> service.cobrar(CREDITO_ID, BigDecimal.ZERO, HOY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mayor a 0");
        assertThatThrownBy(() -> service.cobrar(CREDITO_ID, new BigDecimal("-1"), HOY))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.cobrar(CREDITO_ID, null, HOY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("obligatorio");
    }

    @Test
    void sePuedeCobrarMoraEnUnCreditoYaPagado() {
        // Las cuotas se saldaron, pero la cuota 1 se pagó tarde (11/09): quedan 4.08 de mora por cobrar.
        credito.setEstado(Credito.EstadoCredito.PAGADO);
        Pago p = new Pago();
        p.setId(1L);
        p.setNumeroCuota(1);
        p.setMontoPagado(new BigDecimal("350.14"));
        p.setFechaPago(HOY);
        pagos.add(p);

        MoraService.ResumenMora resumen = service.cobrar(CREDITO_ID, new BigDecimal("4.08"), HOY);

        assertThat(resumen.saldo()).isEqualByComparingTo("0.00");
    }

    @Test
    void noSePuedeRegistrarMoraEnUnCreditoPendienteORechazado() {
        credito.setEstado(Credito.EstadoCredito.PENDIENTE);
        assertThatThrownBy(() -> service.cobrar(CREDITO_ID, new BigDecimal("1.00"), HOY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("estado PENDIENTE");

        credito.setEstado(Credito.EstadoCredito.RECHAZADO);
        assertThatThrownBy(() -> service.condonar(CREDITO_ID, new BigDecimal("1.00"), "motivo", HOY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("estado RECHAZADO");
    }

    // ------------------------------------------------------------ condonación

    @Test
    void condonar_bajaElSaldoYGuardaElMotivo() {
        MoraService.ResumenMora resumen = service.condonar(CREDITO_ID, new BigDecimal("4.08"),
                "  Cliente avisó con anticipación  ", HOY);

        assertThat(resumen.condonado()).isEqualByComparingTo("4.08");
        assertThat(resumen.cobrado()).isEqualByComparingTo("0.00");
        assertThat(resumen.saldo()).isEqualByComparingTo("0.00");

        MovimientoMora m = movimientos.get(0);
        assertThat(m.getTipo()).isEqualTo(TipoMovimientoMora.CONDONACION);
        assertThat(m.getMotivo()).isEqualTo("Cliente avisó con anticipación");   // sin espacios sobrantes
        verify(auditoriaService).registrar(eq(AccionAuditoria.MORA_CONDONADA), eq("Credito"), eq(CREDITO_ID), anyString());
    }

    @Test
    void condonarExigeMotivoYTieneUnMaximoDe300Caracteres() {
        assertThatThrownBy(() -> service.condonar(CREDITO_ID, new BigDecimal("1.00"), "   ", HOY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("obligatorio");
        assertThatThrownBy(() -> service.condonar(CREDITO_ID, new BigDecimal("1.00"), null, HOY))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.condonar(CREDITO_ID, new BigDecimal("1.00"), "x".repeat(301), HOY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("300");
        assertThat(movimientos).isEmpty();
    }

    @Test
    void cobradoMasCondonadoNoPuedenSuperarLoDevengado() {
        service.cobrar(CREDITO_ID, new BigDecimal("3.00"), HOY);
        service.condonar(CREDITO_ID, new BigDecimal("1.00"), "Ajuste", HOY);   // 3.00 + 1.00 = 4.00 de 4.08

        assertThat(service.resumen(CREDITO_ID, HOY).saldo()).isEqualByComparingTo("0.08");
        assertThatThrownBy(() -> service.condonar(CREDITO_ID, new BigDecimal("0.09"), "Ajuste", HOY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("supera el saldo de mora");
    }
}
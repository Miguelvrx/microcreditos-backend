package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.Pago;
import com.microcreditos.microcreditosbackend.repository.MovimientoMoraRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mora de varios créditos a la vez (dashboard y reportes). Mismo crédito de referencia que
 * InteresMoratorioServiceTest: $1000 a 3 meses, cuota 350.14, inicio 01/08/2026, tasa 5% y
 * 3 días de gracia. Con hoy = 11/09/2026 y sin pagos, el devengado de la cuota 1 es 4.08.
 *
 * El repositorio se simula: devuelve filas {creditoId, tipo, suma} como la consulta agrupada real.
 */
class MoraCarteraServiceTest {

    private static final LocalDate INICIO = LocalDate.of(2026, 8, 1);
    private static final LocalDate HOY = LocalDate.of(2026, 9, 11);

    private MovimientoMoraRepository repositorio;
    private MoraCarteraService service;

    @BeforeEach
    void preparar() {
        repositorio = mock(MovimientoMoraRepository.class);
        when(repositorio.sumarPorCreditoYTipo()).thenReturn(List.of());
        service = new MoraCarteraService(new InteresMoratorioService(new BigDecimal("5"), 3), repositorio);
    }

    private Credito credito(long id, Credito.EstadoCredito estado) {
        Credito c = new Credito();
        c.setId(id);
        c.setMontoSolicitado(new BigDecimal("1000.00"));
        c.setTasaInteresMensual(new BigDecimal("2.5"));
        c.setPlazoMeses(3);
        c.setCuotaMensual(new BigDecimal("350.14"));
        c.setFechaInicio(INICIO);
        c.setEstado(estado);
        return c;
    }

    private Pago pagoTotalCuota1(long id, LocalDate fechaPago) {
        Pago p = new Pago();
        p.setId(id);
        p.setNumeroCuota(1);
        p.setMontoPagado(new BigDecimal("350.14"));
        p.setFechaPago(fechaPago);
        p.setFechaVencimiento(INICIO.plusMonths(1));
        p.setEstado(Pago.EstadoPago.PAGADO);
        return p;
    }

    private static Object[] movimiento(long creditoId, String tipo, String suma) {
        return new Object[]{creditoId, tipo, new BigDecimal(suma)};
    }

    private void movimientosEnBase(Object[]... filas) {
        when(repositorio.sumarPorCreditoYTipo()).thenReturn(Arrays.asList(filas));
    }

    // ------------------------------------------------------------ saldo = devengado - cobrado - condonado

    @Test
    void sinMovimientos_elSaldoEsTodoElDevengado() {
        Map<Long, MoraCarteraService.MoraResumen> r =
                service.calcular(List.of(credito(15, Credito.EstadoCredito.EN_MORA)), HOY);

        MoraCarteraService.MoraResumen mora = r.get(15L);
        assertThat(mora.devengado()).isEqualByComparingTo("4.08");
        assertThat(mora.cobrado()).isEqualByComparingTo("0.00");
        assertThat(mora.condonado()).isEqualByComparingTo("0.00");
        assertThat(mora.saldo()).isEqualByComparingTo("4.08");
    }

    @Test
    void cobradoYCondonado_seRestanDelDevengado() {
        movimientosEnBase(movimiento(15, "COBRO", "1.00"), movimiento(15, "CONDONACION", "1.50"));

        MoraCarteraService.MoraResumen mora =
                service.calcular(List.of(credito(15, Credito.EstadoCredito.EN_MORA)), HOY).get(15L);

        assertThat(mora.devengado()).isEqualByComparingTo("4.08");
        assertThat(mora.cobrado()).isEqualByComparingTo("1.00");
        assertThat(mora.condonado()).isEqualByComparingTo("1.50");
        assertThat(mora.saldo()).isEqualByComparingTo("1.58");   // 4.08 - 1.00 - 1.50
    }

    @Test
    void elSaldoNuncaEsNegativo() {
        // Se movió más de lo devengado (por ejemplo, tras una anulación que redujo el devengado).
        movimientosEnBase(movimiento(15, "COBRO", "3.00"), movimiento(15, "CONDONACION", "2.00"));

        MoraCarteraService.MoraResumen mora =
                service.calcular(List.of(credito(15, Credito.EstadoCredito.EN_MORA)), HOY).get(15L);

        assertThat(mora.devengado()).isEqualByComparingTo("4.08");
        assertThat(mora.saldo()).isEqualByComparingTo("0.00");
    }

    @Test
    void losMovimientosDeUnCredito_noSeMezclanConLosDeOtro() {
        movimientosEnBase(movimiento(26, "COBRO", "2.00"));

        Map<Long, MoraCarteraService.MoraResumen> r = service.calcular(List.of(
                credito(15, Credito.EstadoCredito.EN_MORA),
                credito(26, Credito.EstadoCredito.APROBADO)), HOY);

        assertThat(r.get(15L).cobrado()).isEqualByComparingTo("0.00");
        assertThat(r.get(15L).saldo()).isEqualByComparingTo("4.08");
        assertThat(r.get(26L).cobrado()).isEqualByComparingTo("2.00");
        assertThat(r.get(26L).saldo()).isEqualByComparingTo("2.08");
    }

    @Test
    void exponeElDetallePorCuota_paraLosReportes() {
        // Hoy 11/09 sin pagos: solo la cuota 1 tiene mora (7 días, 4.08).
        MoraCarteraService.MoraResumen mora =
                service.calcular(List.of(credito(15, Credito.EstadoCredito.EN_MORA)), HOY).get(15L);

        assertThat(mora.cuotas()).hasSize(1);
        assertThat(mora.cuotas().get(0).numeroCuota()).isEqualTo(1);
        assertThat(mora.cuotas().get(0).diasCobrados()).isEqualTo(7);
        assertThat(mora.cuotas().get(0).interes()).isEqualByComparingTo("4.08");
    }

    // ------------------------------------------------------------ qué créditos entran

    @Test
    void soloEntranLosCreditosQueAdmitenMora() {
        Map<Long, MoraCarteraService.MoraResumen> r = service.calcular(List.of(
                credito(1, Credito.EstadoCredito.PENDIENTE),
                credito(2, Credito.EstadoCredito.RECHAZADO),
                credito(3, Credito.EstadoCredito.APROBADO),
                credito(4, Credito.EstadoCredito.EN_MORA),
                credito(5, Credito.EstadoCredito.PAGADO)), HOY);

        assertThat(r).containsOnlyKeys(3L, 4L, 5L);
    }

    @Test
    void creditoPagadoConAtraso_sigueDebiendoLaMoraGenerada() {
        // Cuota 1 saldada el 11/09, con 7 días de atraso cobrables: 4.08 de mora aunque ya no debe cuotas.
        Credito pagado = credito(25, Credito.EstadoCredito.PAGADO);
        pagado.setPagos(List.of(pagoTotalCuota1(1, HOY)));

        MoraCarteraService.MoraResumen mora = service.calcular(List.of(pagado), HOY).get(25L);

        assertThat(mora.devengado()).isEqualByComparingTo("4.08");
        assertThat(mora.saldo()).isEqualByComparingTo("4.08");
    }

    @Test
    void creditoAlCorriente_tieneMoraCero() {
        // Hoy 20/08: todavía no vence ninguna cuota.
        MoraCarteraService.MoraResumen mora = service
                .calcular(List.of(credito(15, Credito.EstadoCredito.APROBADO)), LocalDate.of(2026, 8, 20))
                .get(15L);

        assertThat(mora.devengado()).isEqualByComparingTo("0.00");
        assertThat(mora.saldo()).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------ eficiencia

    @Test
    void conVariosCreditos_consultaLosMovimientosUnaSolaVez() {
        service.calcular(List.of(
                credito(1, Credito.EstadoCredito.APROBADO),
                credito(2, Credito.EstadoCredito.EN_MORA),
                credito(3, Credito.EstadoCredito.PAGADO)), HOY);

        verify(repositorio, times(1)).sumarPorCreditoYTipo();
    }

    @Test
    void sinCreditos_devuelveUnMapaVacio() {
        assertThat(service.calcular(List.of(), HOY)).isEmpty();
    }
}
package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.Pago;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reglas del interés moratorio. Crédito de referencia: $1000 a 3 meses, cuota 350.14,
 * inicio 01/08/2026, así que las cuotas vencen el 01/09, 01/10 y 01/11.
 * Tasa 5% mensual (tasa diaria = 5/30 %) y 3 días de gracia, salvo donde se indique.
 *
 * Fórmula de cada caso: saldo x días x 5 / 3000, redondeado a 2 decimales.
 */
class InteresMoratorioServiceTest {

    private static final LocalDate INICIO = LocalDate.of(2026, 8, 1);

    private final InteresMoratorioService service = new InteresMoratorioService(new BigDecimal("5"), 3);

    private Credito credito() {
        Credito c = new Credito();
        c.setId(15L);
        c.setMontoSolicitado(new BigDecimal("1000.00"));
        c.setTasaInteresMensual(new BigDecimal("2.5"));
        c.setPlazoMeses(3);
        c.setCuotaMensual(new BigDecimal("350.14"));
        c.setFechaInicio(INICIO);
        return c;
    }

    private Pago pago(long id, int cuota, String monto, LocalDate fechaPago, Long pagoOriginalId) {
        Pago p = new Pago();
        p.setId(id);
        p.setNumeroCuota(cuota);
        p.setMontoPagado(new BigDecimal(monto));
        p.setFechaPago(fechaPago);
        p.setFechaVencimiento(INICIO.plusMonths(cuota));
        p.setEstado(pagoOriginalId == null ? Pago.EstadoPago.PAGADO : Pago.EstadoPago.ANULACION);
        p.setPagoOriginalId(pagoOriginalId);
        return p;
    }

    private static LocalDate fecha(int mes, int dia) {
        return LocalDate.of(2026, mes, dia);
    }

    // ------------------------------------------------------------ gracia y vencimiento

    @Test
    void dentroDeLosDiasDeGracia_noCobraNada() {
        // La cuota 1 venció el 01/09; el 04/09 lleva 3 días (justo la gracia).
        InteresMoratorioService.MoraCredito mora = service.calcular(credito(), List.of(), fecha(9, 4));

        assertThat(mora.cuotas()).isEmpty();
        assertThat(mora.total()).isEqualByComparingTo("0.00");
    }

    @Test
    void diezDiasDeAtrasoConTresDeGracia_cobraSieteDias() {
        // 350.14 x 7 x 5 / 3000 = 4.0849...
        InteresMoratorioService.MoraCredito mora = service.calcular(credito(), List.of(), fecha(9, 11));

        assertThat(mora.cuotas()).hasSize(1);
        assertThat(mora.cuotas().get(0).numeroCuota()).isEqualTo(1);
        assertThat(mora.cuotas().get(0).diasCobrados()).isEqualTo(7);
        assertThat(mora.cuotas().get(0).interes()).isEqualByComparingTo("4.08");
        assertThat(mora.total()).isEqualByComparingTo("4.08");
    }

    @Test
    void sinDiasDeGracia_cobraDesdeElPrimerDiaDeAtraso() {
        InteresMoratorioService sinGracia = new InteresMoratorioService(new BigDecimal("5"), 0);

        // 350.14 x 10 x 5 / 3000 = 5.8356...
        assertThat(sinGracia.calcular(credito(), List.of(), fecha(9, 11)).total()).isEqualByComparingTo("5.84");
    }

    @Test
    void cuotaQueTodaviaNoVence_noGeneraMora() {
        assertThat(service.calcular(credito(), List.of(), fecha(8, 20)).total()).isEqualByComparingTo("0.00");
    }

    @Test
    void elDiaDelVencimiento_noGeneraMora() {
        assertThat(service.calcular(credito(), List.of(), fecha(9, 1)).cuotas()).isEmpty();
    }

    // ------------------------------------------------------------ pagos

    @Test
    void pagoParcial_reduceLaBaseDesdeElDiaSiguienteAlPago() {
        // Pago de 100.00 el 06/09. Días con interés: del 05/09 al 11/09.
        // El 05 y el 06 el saldo al inicio del día es 350.14; del 07 al 11 (5 días) es 250.14.
        // (350.14 x 2 + 250.14 x 5) x 5 / 3000 = 3.2516...
        List<Pago> pagos = List.of(pago(1, 1, "100.00", fecha(9, 6), null));

        InteresMoratorioService.MoraCredito mora = service.calcular(credito(), pagos, fecha(9, 11));

        assertThat(mora.cuotas().get(0).diasCobrados()).isEqualTo(7);
        assertThat(mora.total()).isEqualByComparingTo("3.25");
    }

    @Test
    void pagoTotalDentroDeLaGracia_noGeneraMora() {
        List<Pago> pagos = List.of(pago(1, 1, "350.14", fecha(9, 3), null));

        assertThat(service.calcular(credito(), pagos, fecha(9, 30)).total()).isEqualByComparingTo("0.00");
    }

    @Test
    void pagoTotalTarde_dejaDeAcumularDespuesDePagar() {
        // Pagada el 11/09: cobra del 05 al 11 (7 días) y nada después.
        List<Pago> pagos = List.of(pago(1, 1, "350.14", fecha(9, 11), null));

        assertThat(service.calcular(credito(), pagos, fecha(9, 11)).total()).isEqualByComparingTo("4.08");
        assertThat(service.calcular(credito(), pagos, fecha(9, 20)).total()).isEqualByComparingTo("4.08");
        assertThat(service.calcular(credito(), pagos, fecha(9, 30)).total()).isEqualByComparingTo("4.08");
    }

    @Test
    void anularUnAbono_noCobraMoraRetroactivaPorLosDiasQueEstuvoVigente() {
        // Pagada el 02/09 y anulada el 11/09 (fila negativa con la fecha de la anulación).
        // Hasta el 11/09 no debe nada; desde el 12/09 vuelve a deber: del 12 al 15 son 4 días.
        // 350.14 x 4 x 5 / 3000 = 2.3342...
        List<Pago> pagos = List.of(
                pago(1, 1, "350.14", fecha(9, 2), null),
                pago(2, 1, "-350.14", fecha(9, 11), 1L));

        InteresMoratorioService.MoraCredito mora = service.calcular(credito(), pagos, fecha(9, 15));

        assertThat(mora.cuotas().get(0).diasCobrados()).isEqualTo(4);
        assertThat(mora.total()).isEqualByComparingTo("2.33");
    }

    // ------------------------------------------------------------ varias cuotas y configuración

    @Test
    void variasCuotasVencidas_sumaCadaUnaConSusPropiosDias() {
        // Hoy 11/10 sin pagos. Cuota 1: del 05/09 al 11/10 = 37 días -> 350.14 x 37 x 5 / 3000 = 21.59.
        // Cuota 2 (vence 01/10): del 05/10 al 11/10 = 7 días -> 4.08. La cuota 3 vence el 01/11.
        InteresMoratorioService.MoraCredito mora = service.calcular(credito(), List.of(), fecha(10, 11));

        assertThat(mora.cuotas()).hasSize(2);
        assertThat(mora.cuotas().get(0).diasCobrados()).isEqualTo(37);
        assertThat(mora.cuotas().get(0).interes()).isEqualByComparingTo("21.59");
        assertThat(mora.cuotas().get(1).diasCobrados()).isEqualTo(7);
        assertThat(mora.cuotas().get(1).interes()).isEqualByComparingTo("4.08");
        assertThat(mora.total()).isEqualByComparingTo("25.67");
    }

    @Test
    void tasaCero_apagaElInteresMoratorio() {
        InteresMoratorioService apagado = new InteresMoratorioService(BigDecimal.ZERO, 3);

        assertThat(apagado.calcular(credito(), List.of(), fecha(10, 11)).total()).isEqualByComparingTo("0.00");
    }
}
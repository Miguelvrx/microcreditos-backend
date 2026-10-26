package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.Pago;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas puras (sin Spring ni base de datos) de la lógica de cuotas vencidas
 * y del cronograma. Usan el crédito de referencia del proyecto:
 * $1000 a 3 meses, 2.5% mensual, cuota 350.14, total 1050.42.
 *
 * Las fechas son fijas porque CronogramaService recibe "hoy" por parámetro.
 */
class CronogramaServiceTest {

    private static final LocalDate INICIO = LocalDate.of(2026, 8, 1);   // cuotas vencen 01/09, 01/10, 01/11
    private static final LocalDate HOY = LocalDate.of(2026, 10, 6);

    private final CronogramaService service = new CronogramaService();

    private Credito credito() {
        Credito c = new Credito();
        c.setId(15L);
        c.setMontoSolicitado(new BigDecimal("1000.00"));
        c.setTasaInteresMensual(new BigDecimal("2.5"));
        c.setPlazoMeses(3);
        c.setCuotaMensual(new BigDecimal("350.14"));
        c.setFechaInicio(INICIO);
        c.setEstado(Credito.EstadoCredito.APROBADO);
        return c;
    }

    private Pago pago(long id, int cuota, String monto, Pago.EstadoPago estado, Long pagoOriginalId) {
        Pago p = new Pago();
        p.setId(id);
        p.setNumeroCuota(cuota);
        p.setMontoPagado(new BigDecimal(monto));
        p.setEstado(estado);
        p.setPagoOriginalId(pagoOriginalId);
        p.setFechaVencimiento(INICIO.plusMonths(cuota));
        return p;
    }

    private BigDecimal redondear(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------- cuotas vencidas

    @Test
    void sinPagos_lasCuotasVencidasSonLasQueYaPasaronDeFecha() {
        List<Integer> vencidas = service.cuotasVencidasSinPagar(credito(), List.of(), HOY);

        assertThat(vencidas).containsExactly(1, 2);   // la 3 vence el 01/11
    }

    @Test
    void cuotaQueVenceHoy_noSeConsideraVencida() {
        List<Integer> vencidas = service.cuotasVencidasSinPagar(credito(), List.of(), LocalDate.of(2026, 10, 1));

        assertThat(vencidas).containsExactly(1);   // la cuota 2 vence justo hoy: aún no está en mora
    }

    @Test
    void variosAbonosQueSumanLaCuota_laDejanCompleta() {
        List<Pago> pagos = List.of(
                pago(1, 1, "100.00", Pago.EstadoPago.PAGADO, null),
                pago(2, 1, "100.00", Pago.EstadoPago.PAGADO, null),
                pago(3, 1, "100.00", Pago.EstadoPago.PAGADO, null),
                pago(4, 1, "50.14", Pago.EstadoPago.PAGADO, null));

        assertThat(service.cuotasVencidasSinPagar(credito(), pagos, HOY)).containsExactly(2);
    }

    @Test
    void cuotaParcial_siguesiendoVencida() {
        List<Pago> pagos = List.of(
                pago(1, 1, "350.14", Pago.EstadoPago.PAGADO, null),
                pago(2, 2, "100.00", Pago.EstadoPago.ATRASADO, null));

        assertThat(service.cuotasVencidasSinPagar(credito(), pagos, HOY)).containsExactly(2);
    }

    @Test
    void unaAnulacionCompensaElPago_yLaCuotaVuelveAEstarVencida() {
        List<Pago> pagos = List.of(
                pago(1, 1, "350.14", Pago.EstadoPago.PAGADO, null),
                pago(2, 2, "350.14", Pago.EstadoPago.ATRASADO, null),
                pago(3, 2, "-350.14", Pago.EstadoPago.ANULACION, 2L));

        assertThat(service.cuotasVencidasSinPagar(credito(), pagos, HOY)).containsExactly(2);
    }

    @Test
    void estaEnMora_esFalsoSiTodoLoVencidoEstaCubierto() {
        List<Pago> pagos = List.of(
                pago(1, 1, "350.14", Pago.EstadoPago.PAGADO, null),
                pago(2, 2, "350.14", Pago.EstadoPago.PAGADO, null));

        assertThat(service.estaEnMora(credito(), pagos, HOY)).isFalse();
    }

    @Test
    void estaEnMora_esVerdaderoConUnaCuotaVencidaSinPagar() {
        assertThat(service.estaEnMora(credito(), List.of(), HOY)).isTrue();
    }

    // ---------------------------------------------------------------- cronograma

    @Test
    void generarCronograma_reproduceLaTablaDeAmortizacionDeLaPantalla() {
        Credito c = credito();
        c.setPagos(List.of());

        List<CronogramaService.FilaCronograma> filas = service.generarCronograma(c);

        assertThat(filas).hasSize(3);

        // Cuota 1
        assertThat(redondear(filas.get(0).interes())).isEqualByComparingTo("25.00");
        assertThat(redondear(filas.get(0).capital())).isEqualByComparingTo("325.14");
        assertThat(redondear(filas.get(0).saldoCapital())).isEqualByComparingTo("674.86");
        // Cuota 2
        assertThat(redondear(filas.get(1).interes())).isEqualByComparingTo("16.87");
        assertThat(redondear(filas.get(1).capital())).isEqualByComparingTo("333.27");
        assertThat(redondear(filas.get(1).saldoCapital())).isEqualByComparingTo("341.59");
        // Cuota 3: la última absorbe el ajuste y deja el saldo exactamente en 0
        assertThat(redondear(filas.get(2).interes())).isEqualByComparingTo("8.54");
        assertThat(redondear(filas.get(2).capital())).isEqualByComparingTo("341.59");
        assertThat(filas.get(2).saldoCapital()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void generarCronograma_elCapitalTotalAmortizadoEsExactamenteElMontoSolicitado() {
        Credito c = credito();
        c.setPagos(List.of());

        BigDecimal capitalTotal = service.generarCronograma(c).stream()
                .map(CronogramaService.FilaCronograma::capital)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(capitalTotal).isEqualByComparingTo("1000.00");
    }

    @Test
    void generarCronograma_estadoPorCuota_pendienteParcialYPagado() {
        Credito c = credito();
        c.setPagos(List.of(
                pago(1, 1, "350.14", Pago.EstadoPago.PAGADO, null),
                pago(2, 2, "100.00", Pago.EstadoPago.PAGADO, null)));

        List<CronogramaService.FilaCronograma> filas = service.generarCronograma(c);

        assertThat(filas.get(0).estado()).isEqualTo(CronogramaService.FilaCronograma.EstadoCuota.PAGADO);
        assertThat(filas.get(1).estado()).isEqualTo(CronogramaService.FilaCronograma.EstadoCuota.PARCIAL);
        assertThat(filas.get(1).saldoPendienteCuota()).isEqualByComparingTo("250.14");
        assertThat(filas.get(2).estado()).isEqualTo(CronogramaService.FilaCronograma.EstadoCuota.PENDIENTE);
    }

    @Test
    void generarCronograma_cuotaPagadaConPagoAtrasadoSeMarcaConAtraso() {
        Credito c = credito();
        c.setPagos(List.of(pago(1, 1, "350.14", Pago.EstadoPago.ATRASADO, null)));

        assertThat(service.generarCronograma(c).get(0).estado())
                .isEqualTo(CronogramaService.FilaCronograma.EstadoCuota.PAGADO_CON_ATRASO);
    }

    @Test
    void generarCronograma_elAtrasoDeUnPagoAnuladoYaNoCuenta() {
        Credito c = credito();
        c.setPagos(List.of(
                pago(5, 1, "350.14", Pago.EstadoPago.ATRASADO, null),
                pago(6, 1, "-350.14", Pago.EstadoPago.ANULACION, 5L),
                pago(7, 1, "350.14", Pago.EstadoPago.PAGADO, null)));

        assertThat(service.generarCronograma(c).get(0).estado())
                .isEqualTo(CronogramaService.FilaCronograma.EstadoCuota.PAGADO);
    }
}
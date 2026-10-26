package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.dto.ParDTO;
import com.microcreditos.microcreditosbackend.dto.TramoAgingDTO;
import com.microcreditos.microcreditosbackend.service.CronogramaService.FilaCronograma;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pruebas del aging y el PAR. No usan Spring ni base de datos.
 * Ubicación: src/test/java/com/microcreditos/microcreditosbackend/service/
 */
class IndicadoresRiesgoCarteraTest {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 16);
    private static final BigDecimal CUOTA = new BigDecimal("350.14");

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    /** Fila de un crédito de $1,000 al 2.5% a 3 meses: cuota 350.14, abonado = lo ya pagado de esa cuota. */
    private static FilaCronograma fila(int numeroCuota, LocalDate vencimiento, String saldoCapital, String abonado) {
        BigDecimal abonadoBd = bd(abonado);
        BigDecimal pendiente = CUOTA.subtract(abonadoBd).max(BigDecimal.ZERO);
        return new FilaCronograma(numeroCuota, CUOTA, bd("25"), bd("325.14"), bd(saldoCapital),
                vencimiento, abonadoBd, pendiente, FilaCronograma.EstadoCuota.PENDIENTE);
    }

    /** Crédito con una sola cuota vencida hace "dias" días y sin abonos. */
    private static IndicadoresRiesgoCartera conUnaCuotaVencidaHace(long dias) {
        IndicadoresRiesgoCartera riesgo = new IndicadoresRiesgoCartera(HOY);
        riesgo.agregar(bd("1000"), List.of(fila(1, HOY.minusDays(dias), "674.86", "0")), List.of(1));
        return riesgo;
    }

    private static void assertMonto(String esperado, BigDecimal real) {
        assertEquals(0, bd(esperado).compareTo(real), "esperado " + esperado + " pero fue " + real);
    }

    @Test
    void elDenominadorSumaElCapitalPendienteYUnaCuotaCompletaLoDescuenta() {
        IndicadoresRiesgoCartera riesgo = new IndicadoresRiesgoCartera(HOY);
        // Crédito 1: nada pagado, capital pendiente = 1,000
        riesgo.agregar(bd("1000"), List.of(
                fila(1, HOY.minusDays(45), "674.86", "0"),
                fila(2, HOY.minusDays(15), "349.72", "0"),
                fila(3, HOY.plusDays(15), "0", "0")), List.of(1, 2));
        // Crédito 2: cuota 1 completa, capital pendiente = 674.86
        riesgo.agregar(bd("1000"), List.of(
                fila(1, HOY.minusDays(5), "674.86", "350.14"),
                fila(2, HOY.plusDays(25), "349.72", "0"),
                fila(3, HOY.plusDays(55), "0", "0")), List.of());

        assertMonto("1674.86", riesgo.capitalVigente());
    }

    @Test
    void elAgingRepartePorCuotaYElPorcentajeDelParSeCalculaSobreLaCarteraVigente() {
        IndicadoresRiesgoCartera riesgo = new IndicadoresRiesgoCartera(HOY);
        riesgo.agregar(bd("1000"), List.of(
                fila(1, HOY.minusDays(45), "674.86", "0"),
                fila(2, HOY.minusDays(15), "349.72", "0"),
                fila(3, HOY.plusDays(15), "0", "0")), List.of(1, 2));
        riesgo.agregar(bd("1000"), List.of(
                fila(1, HOY.minusDays(5), "674.86", "350.14"),
                fila(2, HOY.plusDays(25), "349.72", "0"),
                fila(3, HOY.plusDays(55), "0", "0")), List.of());

        List<TramoAgingDTO> aging = riesgo.aging();
        assertMonto("350.14", aging.get(0).monto());   // 1-30: cuota 2
        assertMonto("350.14", aging.get(1).monto());   // 31-60: cuota 1
        assertMonto("0", aging.get(2).monto());
        assertMonto("0", aging.get(3).monto());
        assertEquals(1, aging.get(0).creditos());
        assertEquals(1, aging.get(1).creditos());

        List<ParDTO> par = riesgo.par();
        assertMonto("1000", par.get(0).capitalEnRiesgo());
        assertMonto("59.7", par.get(0).porcentaje());   // 1000 / 1674.86
        assertMonto("0", par.get(1).capitalEnRiesgo());
        assertMonto("0.0", par.get(1).porcentaje());
    }

    @Test
    void treintaDiasDeAtrasoEstanEnElPrimerTramoYNoEntranAlPar30() {
        IndicadoresRiesgoCartera riesgo = conUnaCuotaVencidaHace(30);

        assertMonto("350.14", riesgo.aging().get(0).monto());
        assertMonto("0", riesgo.par().get(0).capitalEnRiesgo());
    }

    @Test
    void treintaYUnDiasPasanAlSegundoTramoYEntranAlPar30() {
        IndicadoresRiesgoCartera riesgo = conUnaCuotaVencidaHace(31);

        assertMonto("350.14", riesgo.aging().get(1).monto());
        assertMonto("100.0", riesgo.par().get(0).porcentaje());
        assertMonto("0.0", riesgo.par().get(1).porcentaje());
    }

    @Test
    void sesentaYUnDiasEntranAlTercerTramoYAlPar60PeroNoAlPar90() {
        IndicadoresRiesgoCartera riesgo = conUnaCuotaVencidaHace(61);

        assertMonto("350.14", riesgo.aging().get(2).monto());
        assertMonto("100.0", riesgo.par().get(1).porcentaje());
        assertMonto("0.0", riesgo.par().get(2).porcentaje());
    }

    @Test
    void masDeNoventaDiasVanAlUltimoTramoYEntranAlPar90() {
        IndicadoresRiesgoCartera riesgo = conUnaCuotaVencidaHace(91);

        assertMonto("350.14", riesgo.aging().get(3).monto());
        assertMonto("100.0", riesgo.par().get(2).porcentaje());
    }

    @Test
    void unCreditoConCuotasEnVariosTramosSeReparteEnElAgingPeroElParUsaLaMasAntigua() {
        IndicadoresRiesgoCartera riesgo = new IndicadoresRiesgoCartera(HOY);
        riesgo.agregar(bd("1000"), List.of(
                fila(1, HOY.minusDays(70), "674.86", "0"),
                fila(2, HOY.minusDays(40), "349.72", "0"),
                fila(3, HOY.minusDays(10), "0", "0")), List.of(1, 2, 3));

        assertMonto("350.14", riesgo.aging().get(0).monto());
        assertMonto("350.14", riesgo.aging().get(1).monto());
        assertMonto("350.14", riesgo.aging().get(2).monto());
        assertEquals(1, riesgo.aging().get(2).creditos());
        // Su cuota más antigua tiene 70 días: entra al PAR 30 y al PAR 60, no al PAR 90
        assertMonto("100.0", riesgo.par().get(0).porcentaje());
        assertMonto("100.0", riesgo.par().get(1).porcentaje());
        assertMonto("0.0", riesgo.par().get(2).porcentaje());
        assertEquals(1, riesgo.par().get(1).creditos());
    }

    @Test
    void unAbonoParcialReduceLoVencidoPeroNoElCapitalPendiente() {
        IndicadoresRiesgoCartera riesgo = new IndicadoresRiesgoCartera(HOY);
        riesgo.agregar(bd("1000"), List.of(fila(1, HOY.minusDays(10), "674.86", "200")), List.of(1));

        assertMonto("150.14", riesgo.aging().get(0).monto());
        assertMonto("1000", riesgo.capitalVigente());
    }

    @Test
    void sinCarteraVigenteElParEsCeroYNoDivideEntreCero() {
        IndicadoresRiesgoCartera riesgo = new IndicadoresRiesgoCartera(HOY);

        assertMonto("0", riesgo.capitalVigente());
        assertEquals(4, riesgo.aging().size());
        assertEquals(3, riesgo.par().size());
        assertMonto("0.0", riesgo.par().get(0).porcentaje());
    }

    @Test
    void unCreditoConTodasLasCuotasCompletasNoSumaCapital() {
        IndicadoresRiesgoCartera riesgo = new IndicadoresRiesgoCartera(HOY);
        riesgo.agregar(bd("1000"), List.of(
                fila(1, HOY.minusDays(70), "674.86", "350.14"),
                fila(2, HOY.minusDays(40), "349.72", "350.14"),
                fila(3, HOY.minusDays(10), "0", "350.14")), List.of());

        assertMonto("0", riesgo.capitalVigente());
    }

    @Test
    void laSumaDelAgingEsIgualAlTotalVencido() {
        IndicadoresRiesgoCartera riesgo = new IndicadoresRiesgoCartera(HOY);
        riesgo.agregar(bd("1000"), List.of(
                fila(1, HOY.minusDays(100), "674.86", "100"),
                fila(2, HOY.minusDays(70), "349.72", "0"),
                fila(3, HOY.minusDays(20), "0", "0")), List.of(1, 2, 3));

        BigDecimal sumaAging = riesgo.aging().stream()
                .map(TramoAgingDTO::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 250.14 + 350.14 + 350.14
        assertMonto("950.42", sumaAging);
    }
}
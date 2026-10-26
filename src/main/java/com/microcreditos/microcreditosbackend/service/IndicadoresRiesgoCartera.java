package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.dto.ParDTO;
import com.microcreditos.microcreditosbackend.dto.TramoAgingDTO;
import com.microcreditos.microcreditosbackend.service.CronogramaService.FilaCronograma;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Acumula, crédito por crédito, el aging y el PAR de la cartera vigente.
 * No usa Spring ni base de datos: recibe el cronograma ya calculado, así que se prueba solo.
 *
 * Definiciones:
 * - Días de atraso de una cuota vencida = días entre su vencimiento y hoy.
 * - Aging: por cuota. Lo que falta por cobrar de cada cuota vencida (saldoPendienteCuota) va al
 *   tramo que le toca. Un crédito puede repartirse en varios tramos. La suma de los 4 tramos es
 *   igual al "En mora" del dashboard, porque ambos usan las mismas cuotas vencidas.
 * - PAR N: por crédito. Si la cuota vencida más antigua del crédito tiene más de N días de
 *   atraso, todo su capital pendiente cuenta como capital en riesgo.
 * - Capital pendiente: monto solicitado menos el capital de las cuotas ya completas (con la
 *   regla FIFO son siempre las primeras). Un abono parcial no lo baja hasta completar la cuota.
 * - Denominador del PAR: suma del capital pendiente de todos los créditos que se agregan
 *   (el llamador solo agrega los vigentes).
 */
public class IndicadoresRiesgoCartera {

    private static final String[] ETIQUETAS = {"1–30 días", "31–60 días", "61–90 días", "Más de 90 días"};
    private static final int[] UMBRALES_PAR = {30, 60, 90};

    private final LocalDate hoy;
    private BigDecimal capitalVigente = BigDecimal.ZERO;
    private final BigDecimal[] montoTramo = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    private final long[] creditosTramo = new long[4];
    private final BigDecimal[] capitalPar = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    private final long[] creditosPar = new long[3];

    public IndicadoresRiesgoCartera(LocalDate hoy) {
        this.hoy = hoy;
    }

    /**
     * @param montoSolicitado capital original del crédito
     * @param cronograma      filas del cronograma del crédito (CronogramaService.generarCronograma)
     * @param cuotasVencidas  números de cuota vencidas y sin completar (CronogramaService.cuotasVencidasSinPagar)
     */
    public void agregar(BigDecimal montoSolicitado, List<FilaCronograma> cronograma, List<Integer> cuotasVencidas) {
        List<FilaCronograma> filas = cronograma != null ? cronograma : List.of();

        BigDecimal capitalPendiente = capitalPendiente(montoSolicitado, filas);
        capitalVigente = capitalVigente.add(capitalPendiente);

        if (cuotasVencidas == null || cuotasVencidas.isEmpty()) return;

        boolean[] tocaTramo = new boolean[4];
        long maxDiasAtraso = 0;

        for (FilaCronograma fila : filas) {
            if (!cuotasVencidas.contains(fila.numeroCuota())) continue;
            if (fila.fechaVencimiento() == null || fila.saldoPendienteCuota() == null) continue;

            long dias = ChronoUnit.DAYS.between(fila.fechaVencimiento(), hoy);
            if (dias < 1) continue;

            int tramo = tramo(dias);
            montoTramo[tramo] = montoTramo[tramo].add(fila.saldoPendienteCuota());
            tocaTramo[tramo] = true;
            maxDiasAtraso = Math.max(maxDiasAtraso, dias);
        }

        for (int t = 0; t < tocaTramo.length; t++) {
            if (tocaTramo[t]) creditosTramo[t]++;
        }
        for (int i = 0; i < UMBRALES_PAR.length; i++) {
            if (maxDiasAtraso > UMBRALES_PAR[i]) {
                capitalPar[i] = capitalPar[i].add(capitalPendiente);
                creditosPar[i]++;
            }
        }
    }

    public BigDecimal capitalVigente() {
        return redondear(capitalVigente);
    }

    public List<TramoAgingDTO> aging() {
        List<TramoAgingDTO> tramos = new ArrayList<>();
        for (int t = 0; t < ETIQUETAS.length; t++) {
            tramos.add(new TramoAgingDTO(ETIQUETAS[t], redondear(montoTramo[t]), creditosTramo[t]));
        }
        return tramos;
    }

    public List<ParDTO> par() {
        List<ParDTO> indicadores = new ArrayList<>();
        for (int i = 0; i < UMBRALES_PAR.length; i++) {
            indicadores.add(new ParDTO(UMBRALES_PAR[i], redondear(capitalPar[i]),
                    porcentaje(capitalPar[i]), creditosPar[i]));
        }
        return indicadores;
    }

    /** Monto solicitado menos el capital de las cuotas completas, contando desde la primera (FIFO). */
    static BigDecimal capitalPendiente(BigDecimal montoSolicitado, List<FilaCronograma> filas) {
        BigDecimal pendiente = montoSolicitado;
        for (FilaCronograma fila : filas) {
            if (fila.saldoPendienteCuota() == null || fila.saldoPendienteCuota().signum() > 0) break;
            if (fila.saldoCapital() != null) pendiente = fila.saldoCapital();
        }
        return pendiente;
    }

    private static int tramo(long dias) {
        if (dias <= 30) return 0;
        if (dias <= 60) return 1;
        if (dias <= 90) return 2;
        return 3;
    }

    private BigDecimal porcentaje(BigDecimal capitalEnRiesgo) {
        if (capitalVigente.signum() <= 0) return BigDecimal.ZERO.setScale(1);
        return capitalEnRiesgo.multiply(BigDecimal.valueOf(100))
                .divide(capitalVigente, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal redondear(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP);
    }
}
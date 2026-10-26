package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.Pago;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Calcula el interés moratorio DEVENGADO de un crédito a una fecha. No guarda nada: sale
 * de los datos que ya existen (cuotas, vencimientos y pagos con su fecha).
 *
 * Regla:
 *  - Cada cuota vencida con saldo pendiente genera interés por cada día de atraso, DESPUÉS de
 *    los días de gracia: con 3 de gracia, una cuota vencida hace 10 días cobra 7 días.
 *  - El interés de un día se calcula sobre el saldo de la cuota AL INICIO de ese día (los pagos
 *    con fechaPago anterior a ese día). Un pago de hoy deja de acumular desde mañana.
 *  - Tasa diaria = tasa mensual / 30. La tasa es un porcentaje global (5 = 5% mensual).
 *  - Las anulaciones (filas con monto negativo y la fecha de la anulación) reabren el saldo
 *    desde esa fecha: anular un abono NO cobra mora retroactiva por los días en que estuvo vigente.
 *  - Se redondea a 2 decimales (HALF_UP) por cuota; el total es la suma de las cuotas.
 *  - Una tasa de 0 apaga el interés moratorio.
 *
 * El estado EN_MORA no depende de la gracia: sigue siendo "hay una cuota vencida sin pagar".
 * Este servicio solo calcula cuánto interés se ha generado, no cuánto se ha cobrado.
 */
@Service
public class InteresMoratorioService {

    private static final BigDecimal DIVISOR = BigDecimal.valueOf(100L * 30L);   // % y 30 días por mes

    private final BigDecimal tasaMensual;
    private final int diasGracia;

    public InteresMoratorioService(
            @Value("${mora.tasa-mensual:5}") BigDecimal tasaMensual,
            @Value("${mora.dias-gracia:3}") int diasGracia) {
        this.tasaMensual = tasaMensual;
        this.diasGracia = diasGracia;
    }

    public record MoraCuota(int numeroCuota, long diasCobrados, BigDecimal interes) {}

    public record MoraCredito(List<MoraCuota> cuotas, BigDecimal total) {}

    public MoraCredito calcular(Credito credito, List<Pago> pagos, LocalDate hoy) {
        List<MoraCuota> cuotas = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);

        if (tasaMensual.signum() <= 0) {
            return new MoraCredito(cuotas, total);
        }
        List<Pago> todos = pagos == null ? List.of() : pagos;

        for (int numero = 1; numero <= credito.getPlazoMeses(); numero++) {
            LocalDate inicioCobro = credito.getFechaInicio().plusMonths(numero).plusDays(diasGracia);
            if (ChronoUnit.DAYS.between(inicioCobro, hoy) <= 0) {
                continue;   // todavía dentro del plazo o de la gracia
            }

            final int cuotaActual = numero;
            List<Pago> deLaCuota = todos.stream()
                    .filter(p -> p.getNumeroCuota().equals(cuotaActual))
                    .toList();

            BigDecimal saldoPorDias = BigDecimal.ZERO;   // suma de (saldo de cada día con atraso)
            long diasConSaldo = 0;

            for (LocalDate dia = inicioCobro.plusDays(1); !dia.isAfter(hoy); dia = dia.plusDays(1)) {
                final LocalDate d = dia;
                BigDecimal pagadoAntesDelDia = deLaCuota.stream()
                        .filter(p -> p.getFechaPago().isBefore(d))
                        .map(Pago::getMontoPagado)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal saldo = credito.getCuotaMensual().subtract(pagadoAntesDelDia);

                if (saldo.signum() > 0) {
                    saldoPorDias = saldoPorDias.add(saldo);
                    diasConSaldo++;
                }
            }

            if (diasConSaldo > 0) {
                BigDecimal interes = saldoPorDias.multiply(tasaMensual).divide(DIVISOR, 2, RoundingMode.HALF_UP);
                cuotas.add(new MoraCuota(numero, diasConSaldo, interes));
                total = total.add(interes);
            }
        }

        return new MoraCredito(cuotas, total);
    }
}
package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.Pago;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calcula, a partir de los datos ya guardados de un crédito (sin persistir
 * nada nuevo), qué cuotas de su cronograma teórico ya vencieron y no están
 * completas. Una cuota se considera completa cuando la suma de sus Pagos
 * (abonos) cubre cuotaMensual — no basta con que exista un Pago.
 *
 * Las anulaciones son filas con monto negativo, así que las sumas por cuota
 * ya las compensan sin lógica adicional.
 */
@Service
public class CronogramaService {

    public List<Integer> cuotasVencidasSinPagar(Credito credito, LocalDate hoy) {
        return cuotasVencidasSinPagar(credito, credito.getPagos(), hoy);
    }

    /**
     * Misma regla, pero evaluando una lista de pagos explícita. Se usa cuando
     * la colección credito.getPagos() puede estar desactualizada dentro de una
     * transacción (por ejemplo, justo después de insertar anulaciones).
     */
    public List<Integer> cuotasVencidasSinPagar(Credito credito, List<Pago> pagos, LocalDate hoy) {
        Map<Integer, BigDecimal> acumuladoPorCuota = new HashMap<>();
        if (pagos != null) {
            for (Pago pago : pagos) {
                acumuladoPorCuota.merge(pago.getNumeroCuota(), pago.getMontoPagado(), BigDecimal::add);
            }
        }

        List<Integer> vencidasSinPagar = new ArrayList<>();
        for (int numeroCuota = 1; numeroCuota <= credito.getPlazoMeses(); numeroCuota++) {
            LocalDate fechaVencimiento = credito.getFechaInicio().plusMonths(numeroCuota);
            BigDecimal acumulado = acumuladoPorCuota.getOrDefault(numeroCuota, BigDecimal.ZERO);
            boolean estaCompleta = acumulado.compareTo(credito.getCuotaMensual()) >= 0;
            if (fechaVencimiento.isBefore(hoy) && !estaCompleta) {
                vencidasSinPagar.add(numeroCuota);
            }
        }
        return vencidasSinPagar;
    }

    public boolean estaEnMora(Credito credito, LocalDate hoy) {
        return !cuotasVencidasSinPagar(credito, hoy).isEmpty();
    }

    public boolean estaEnMora(Credito credito, List<Pago> pagos, LocalDate hoy) {
        return !cuotasVencidasSinPagar(credito, pagos, hoy).isEmpty();
    }

    /**
     * Reconstruye el cronograma completo de un crédito, cuota por cuota, con
     * el mismo cálculo que ya usa el frontend en
     * CreditoDetalleModal.generarTablaAmortizacion(): amortización francesa
     * (interés sobre saldo, capital = cuota - interés, ajuste en la última
     * cuota) y el mismo criterio de estado por cuota (agrupando los Pagos por
     * numeroCuota). Es la fuente única para el reporte de pagos, para que no
     * pueda desalinearse de lo que el usuario ve en pantalla.
     */
    public List<FilaCronograma> generarCronograma(Credito credito) {
        Map<Integer, List<Pago>> pagosPorCuota = new HashMap<>();
        // Ids de pagos que ya fueron anulados: su atraso original no debe seguir contando.
        Set<Long> idsAnulados = new HashSet<>();
        if (credito.getPagos() != null) {
            for (Pago pago : credito.getPagos()) {
                pagosPorCuota.computeIfAbsent(pago.getNumeroCuota(), k -> new ArrayList<>()).add(pago);
                if (pago.getPagoOriginalId() != null) {
                    idsAnulados.add(pago.getPagoOriginalId());
                }
            }
        }

        BigDecimal tasaDecimal = credito.getTasaInteresMensual().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        BigDecimal saldoCapital = credito.getMontoSolicitado();
        List<FilaCronograma> filas = new ArrayList<>();

        for (int numeroCuota = 1; numeroCuota <= credito.getPlazoMeses(); numeroCuota++) {
            BigDecimal interes = saldoCapital.multiply(tasaDecimal);
            BigDecimal capital = credito.getCuotaMensual().subtract(interes);
            if (numeroCuota == credito.getPlazoMeses() || capital.compareTo(saldoCapital) > 0) {
                capital = saldoCapital;
            }
            saldoCapital = saldoCapital.subtract(capital).max(BigDecimal.ZERO);

            LocalDate fechaVencimiento = credito.getFechaInicio().plusMonths(numeroCuota);

            List<Pago> abonos = pagosPorCuota.getOrDefault(numeroCuota, List.of());
            BigDecimal totalAbonado = abonos.stream()
                    .map(Pago::getMontoPagado)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal saldoPendienteCuota = credito.getCuotaMensual().subtract(totalAbonado).max(BigDecimal.ZERO);
            boolean completa = saldoPendienteCuota.compareTo(BigDecimal.ZERO) <= 0;

            FilaCronograma.EstadoCuota estado;
            if (totalAbonado.compareTo(BigDecimal.ZERO) == 0) {
                estado = FilaCronograma.EstadoCuota.PENDIENTE;
            } else if (!completa) {
                estado = FilaCronograma.EstadoCuota.PARCIAL;
            } else {
                boolean huboAtraso = abonos.stream().anyMatch(p ->
                        p.getEstado() == Pago.EstadoPago.ATRASADO && !idsAnulados.contains(p.getId()));
                estado = huboAtraso ? FilaCronograma.EstadoCuota.PAGADO_CON_ATRASO : FilaCronograma.EstadoCuota.PAGADO;
            }

            filas.add(new FilaCronograma(numeroCuota, credito.getCuotaMensual(), interes, capital, saldoCapital,
                    fechaVencimiento, totalAbonado, saldoPendienteCuota, estado));
        }

        return filas;
    }

    public record FilaCronograma(
            int numeroCuota,
            BigDecimal cuotaMensual,
            BigDecimal interes,
            BigDecimal capital,
            BigDecimal saldoCapital,
            LocalDate fechaVencimiento,
            BigDecimal totalAbonado,
            BigDecimal saldoPendienteCuota,
            EstadoCuota estado
    ) {
        public enum EstadoCuota {
            PENDIENTE, PARCIAL, PAGADO, PAGADO_CON_ATRASO
        }
    }
}
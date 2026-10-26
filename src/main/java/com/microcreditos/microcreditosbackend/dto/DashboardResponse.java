package com.microcreditos.microcreditosbackend.dto;

import java.math.BigDecimal;
import java.util.List;

public record DashboardResponse(
        BigDecimal totalPrestado,
        BigDecimal totalCobrado,
        BigDecimal totalEnMora,
        long creditosVigentes,
        long creditosEnMora,
        List<CreditoEnMoraDTO> creditosEnMoraDetalle,
        // Interés moratorio de toda la cartera desembolsada (activa y pagada)
        BigDecimal moraDevengada,
        BigDecimal moraCobrada,
        BigDecimal moraCondonada,
        BigDecimal saldoMora,
        long creditosConSaldoMora,
        // Cada crédito con saldo de mora > 0 (incluye PAGADO), de mayor a menor
        List<CreditoConSaldoMoraDTO> creditosConSaldoMoraDetalle,
        // Saldo de capital pendiente de la cartera vigente (APROBADO y EN_MORA): el denominador del PAR
        BigDecimal saldoCapitalVigente,
        // Aging: 4 tramos (1-30, 31-60, 61-90, más de 90 días), siempre en ese orden
        List<TramoAgingDTO> aging,
        // PAR 30, 60 y 90, siempre en ese orden
        List<ParDTO> par
) {
    /**
     * Constructor con los campos de antes del punto 40. Deja los indicadores de riesgo en
     * cero, para no romper código que ya construye la respuesta sin ellos.
     */
    public DashboardResponse(
            BigDecimal totalPrestado,
            BigDecimal totalCobrado,
            BigDecimal totalEnMora,
            long creditosVigentes,
            long creditosEnMora,
            List<CreditoEnMoraDTO> creditosEnMoraDetalle,
            BigDecimal moraDevengada,
            BigDecimal moraCobrada,
            BigDecimal moraCondonada,
            BigDecimal saldoMora,
            long creditosConSaldoMora,
            List<CreditoConSaldoMoraDTO> creditosConSaldoMoraDetalle
    ) {
        this(totalPrestado, totalCobrado, totalEnMora, creditosVigentes, creditosEnMora,
                creditosEnMoraDetalle, moraDevengada, moraCobrada, moraCondonada, saldoMora,
                creditosConSaldoMora, creditosConSaldoMoraDetalle,
                BigDecimal.ZERO, List.of(), List.of());
    }
}
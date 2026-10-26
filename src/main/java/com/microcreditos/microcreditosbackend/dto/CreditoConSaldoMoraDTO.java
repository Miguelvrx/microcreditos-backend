package com.microcreditos.microcreditosbackend.dto;

import java.math.BigDecimal;

/**
 * Un crédito con interés moratorio por cobrar para el dashboard. A diferencia de
 * CreditoEnMoraDTO (créditos con cuotas vencidas), este incluye también créditos
 * PAGADO que quedaron debiendo mora.
 */
public record CreditoConSaldoMoraDTO(
        Long creditoId,
        String clienteNombre,
        String estado,
        BigDecimal saldoMora
) {}
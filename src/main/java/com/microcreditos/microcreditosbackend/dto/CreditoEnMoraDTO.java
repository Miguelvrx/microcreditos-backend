package com.microcreditos.microcreditosbackend.dto;

import java.math.BigDecimal;

public record CreditoEnMoraDTO(
        Long creditoId,
        String clienteNombre,
        BigDecimal montoEnMora,
        int cuotasVencidas,
        BigDecimal saldoMora
) {}
package com.microcreditos.microcreditosbackend.dto;

import java.math.BigDecimal;

/**
 * Portfolio at Risk a "dias" días. capitalEnRiesgo es el saldo de capital pendiente de los
 * créditos cuya cuota vencida más antigua supera "dias" días; porcentaje es ese capital
 * dividido entre el saldo de capital de la cartera vigente, por 100, con un decimal.
 */
public record ParDTO(
        int dias,
        BigDecimal capitalEnRiesgo,
        BigDecimal porcentaje,
        long creditos
) {}
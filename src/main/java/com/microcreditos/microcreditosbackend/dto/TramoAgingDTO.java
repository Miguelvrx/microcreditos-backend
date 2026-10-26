package com.microcreditos.microcreditosbackend.dto;

import java.math.BigDecimal;

/**
 * Un tramo del aging: lo que falta por cobrar de las cuotas vencidas cuyo atraso
 * cae en ese rango de días. "creditos" cuenta cada crédito una vez por tramo, así que
 * un crédito con cuotas en dos tramos distintos aparece en ambos.
 */
public record TramoAgingDTO(
        String etiqueta,
        BigDecimal monto,
        long creditos
) {}
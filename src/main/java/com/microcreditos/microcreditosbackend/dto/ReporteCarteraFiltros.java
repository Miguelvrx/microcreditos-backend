package com.microcreditos.microcreditosbackend.dto;

import com.microcreditos.microcreditosbackend.model.Credito;

import java.time.LocalDate;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public record ReporteCarteraFiltros(
        List<Credito.EstadoCredito> estados,
        Long clienteId,
        LocalDate fechaDesde,
        LocalDate fechaHasta
) {
    public static final List<Credito.EstadoCredito> ESTADOS_PREDETERMINADOS =
            List.of(Credito.EstadoCredito.APROBADO, Credito.EstadoCredito.EN_MORA);

    /** Sin filtro alguno, o solo con los estados que el panel trae marcados de entrada. */
    public boolean esVistaPredeterminada() {
        boolean estadosPredeterminados = estados == null || estados.isEmpty()
                || new HashSet<>(estados).equals(Set.copyOf(ESTADOS_PREDETERMINADOS));
        return estadosPredeterminados
                && clienteId == null
                && fechaDesde == null
                && fechaHasta == null;
    }
}
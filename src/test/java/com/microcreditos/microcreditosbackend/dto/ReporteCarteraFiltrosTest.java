
package com.microcreditos.microcreditosbackend.dto;

import com.microcreditos.microcreditosbackend.model.Credito.EstadoCredito;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReporteCarteraFiltrosTest {

    private static final List<EstadoCredito> DEFAULT = List.of(EstadoCredito.APROBADO, EstadoCredito.EN_MORA);

    @Test
    void sinNada_esVistaPredeterminada() {
        assertThat(new ReporteCarteraFiltros(null, null, null, null).esVistaPredeterminada()).isTrue();
        assertThat(new ReporteCarteraFiltros(List.of(), null, null, null).esVistaPredeterminada()).isTrue();
    }

    @Test
    void estadosPredeterminados_enCualquierOrden_sonVistaPredeterminada() {
        assertThat(new ReporteCarteraFiltros(DEFAULT, null, null, null).esVistaPredeterminada()).isTrue();
        assertThat(new ReporteCarteraFiltros(List.of(EstadoCredito.EN_MORA, EstadoCredito.APROBADO),
                null, null, null).esVistaPredeterminada()).isTrue();
    }

    @Test
    void unSoloEstado_esFiltrado() {
        assertThat(new ReporteCarteraFiltros(List.of(EstadoCredito.EN_MORA), null, null, null)
                .esVistaPredeterminada()).isFalse();
    }

    @Test
    void agregarPagado_esFiltrado() {
        assertThat(new ReporteCarteraFiltros(
                List.of(EstadoCredito.APROBADO, EstadoCredito.EN_MORA, EstadoCredito.PAGADO), null, null, null)
                .esVistaPredeterminada()).isFalse();
    }

    @Test
    void estadosPredeterminadosConCliente_esFiltrado() {
        assertThat(new ReporteCarteraFiltros(DEFAULT, 99L, null, null).esVistaPredeterminada()).isFalse();
    }

    @Test
    void estadosPredeterminadosConFecha_esFiltrado() {
        assertThat(new ReporteCarteraFiltros(DEFAULT, null, LocalDate.of(2026, 10, 1), null)
                .esVistaPredeterminada()).isFalse();
        assertThat(new ReporteCarteraFiltros(DEFAULT, null, null, LocalDate.of(2026, 10, 31))
                .esVistaPredeterminada()).isFalse();
    }
}
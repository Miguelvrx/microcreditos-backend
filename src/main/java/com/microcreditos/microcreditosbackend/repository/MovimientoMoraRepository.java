package com.microcreditos.microcreditosbackend.repository;

import com.microcreditos.microcreditosbackend.model.MovimientoMora;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MovimientoMoraRepository extends JpaRepository<MovimientoMora, Long> {

    List<MovimientoMora> findByCreditoIdOrderByIdDesc(Long creditoId);

    /**
     * Suma de los movimientos de mora agrupada por crédito y tipo (COBRO / CONDONACION),
     * en una sola consulta. Cada fila es {creditoId, tipo, suma}. La usa MoraCarteraService
     * para el dashboard y los reportes, para no consultar crédito por crédito.
     */
    @Query("""
            SELECT m.creditoId, m.tipo, SUM(m.monto)
            FROM MovimientoMora m
            GROUP BY m.creditoId, m.tipo
            """)
    List<Object[]> sumarPorCreditoYTipo();
}
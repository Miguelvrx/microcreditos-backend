package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.repository.MovimientoMoraRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Calcula el interés moratorio de VARIOS créditos a la vez, para el dashboard y los reportes.
 *
 * Por cada crédito: devengado (InteresMoratorioService, al vuelo), cobrado y condonado
 * (suma de movimientos_mora, traída en UNA sola consulta agrupada) y saldo
 * = devengado - cobrado - condonado, que nunca es negativo (misma regla que MoraService.resumen).
 *
 * Solo se calcula para créditos que admiten mora: APROBADO, EN_MORA y PAGADO (un cliente puede
 * saldar las cuotas y quedar debiendo mora). Los demás no aparecen en el mapa devuelto.
 */
@Service
public class MoraCarteraService {

    private final InteresMoratorioService interesMoratorioService;
    private final MovimientoMoraRepository movimientoMoraRepository;

    public MoraCarteraService(InteresMoratorioService interesMoratorioService,
                              MovimientoMoraRepository movimientoMoraRepository) {
        this.interesMoratorioService = interesMoratorioService;
        this.movimientoMoraRepository = movimientoMoraRepository;
    }

    /** cuotas: interés devengado de cada cuota con mora (para el detalle de los reportes). */
    public record MoraResumen(BigDecimal devengado, BigDecimal cobrado,
                              BigDecimal condonado, BigDecimal saldo,
                              List<InteresMoratorioService.MoraCuota> cuotas) {}

    /** Devuelve un mapa creditoId -> resumen de mora, solo con los créditos que admiten mora. */
    public Map<Long, MoraResumen> calcular(List<Credito> creditos, LocalDate hoy) {
        Map<Long, BigDecimal> cobradoPorCredito = new HashMap<>();
        Map<Long, BigDecimal> condonadoPorCredito = new HashMap<>();

        for (Object[] fila : movimientoMoraRepository.sumarPorCreditoYTipo()) {
            Long creditoId = ((Number) fila[0]).longValue();
            String tipo = String.valueOf(fila[1]);
            BigDecimal suma = fila[2] instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(fila[2]));

            if ("COBRO".equals(tipo)) {
                cobradoPorCredito.put(creditoId, suma);
            } else if ("CONDONACION".equals(tipo)) {
                condonadoPorCredito.put(creditoId, suma);
            }
        }

        Map<Long, MoraResumen> resultado = new HashMap<>();
        for (Credito credito : creditos) {
            if (!admiteMora(credito)) continue;

            InteresMoratorioService.MoraCredito interes =
                    interesMoratorioService.calcular(credito, credito.getPagos(), hoy);
            BigDecimal devengado = interes.total();
            BigDecimal cobrado = cobradoPorCredito.getOrDefault(credito.getId(), BigDecimal.ZERO);
            BigDecimal condonado = condonadoPorCredito.getOrDefault(credito.getId(), BigDecimal.ZERO);
            BigDecimal saldo = devengado.subtract(cobrado).subtract(condonado).max(BigDecimal.ZERO);

            resultado.put(credito.getId(), new MoraResumen(devengado, cobrado, condonado, saldo, interes.cuotas()));
        }
        return resultado;
    }

    private boolean admiteMora(Credito credito) {
        return credito.getEstado() == Credito.EstadoCredito.APROBADO
                || credito.getEstado() == Credito.EstadoCredito.EN_MORA
                || credito.getEstado() == Credito.EstadoCredito.PAGADO;
    }
}
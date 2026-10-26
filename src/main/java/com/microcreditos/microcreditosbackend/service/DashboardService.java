package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.dto.CreditoConSaldoMoraDTO;
import com.microcreditos.microcreditosbackend.dto.CreditoEnMoraDTO;
import com.microcreditos.microcreditosbackend.dto.DashboardResponse;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.Pago;
import com.microcreditos.microcreditosbackend.repository.CreditoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    @Autowired
    private CreditoRepository creditoRepository;

    @Autowired
    private CronogramaService cronogramaService;

    @Autowired
    private MoraCarteraService moraCarteraService;

    public DashboardResponse obtenerResumen() {
        LocalDate hoy = LocalDate.now();
        List<Credito> creditos = creditoRepository.findAll();

        // Interés moratorio de toda la cartera (APROBADO, EN_MORA y PAGADO) en una sola pasada.
        Map<Long, MoraCarteraService.MoraResumen> moraPorCredito = moraCarteraService.calcular(creditos, hoy);

        // Aging y PAR de la cartera vigente (solo APROBADO y EN_MORA).
        IndicadoresRiesgoCartera riesgo = new IndicadoresRiesgoCartera(hoy);

        BigDecimal totalPrestado = BigDecimal.ZERO;
        BigDecimal totalCobrado = BigDecimal.ZERO;
        BigDecimal totalEnMora = BigDecimal.ZERO;
        BigDecimal moraDevengada = BigDecimal.ZERO;
        BigDecimal moraCobrada = BigDecimal.ZERO;
        BigDecimal moraCondonada = BigDecimal.ZERO;
        BigDecimal saldoMora = BigDecimal.ZERO;
        long creditosVigentes = 0;
        long creditosEnMora = 0;
        List<CreditoEnMoraDTO> detalleMora = new ArrayList<>();
        List<CreditoConSaldoMoraDTO> detalleSaldoMora = new ArrayList<>();

        for (Credito credito : creditos) {
            boolean esActivo = credito.getEstado() == Credito.EstadoCredito.APROBADO
                    || credito.getEstado() == Credito.EstadoCredito.EN_MORA;
            boolean esPagado = credito.getEstado() == Credito.EstadoCredito.PAGADO;

            // Prestado y cobrado son históricos: cuentan todo crédito que alguna
            // vez se desembolsó (activo o ya saldado). PENDIENTE y RECHAZADO no
            // llegaron a desembolsarse, así que se excluyen.
            if (!esActivo && !esPagado) continue;

            totalPrestado = totalPrestado.add(credito.getMontoSolicitado());

            if (credito.getPagos() != null) {
                for (Pago pago : credito.getPagos()) {
                    totalCobrado = totalCobrado.add(pago.getMontoPagado());
                }
            }

            // El interés moratorio también cuenta en créditos PAGADO: el cliente puede
            // haber saldado las cuotas y seguir debiendo mora.
            MoraCarteraService.MoraResumen mora = moraPorCredito.get(credito.getId());
            if (mora != null) {
                moraDevengada = moraDevengada.add(mora.devengado());
                moraCobrada = moraCobrada.add(mora.cobrado());
                moraCondonada = moraCondonada.add(mora.condonado());
                saldoMora = saldoMora.add(mora.saldo());
                if (mora.saldo().signum() > 0) {
                    detalleSaldoMora.add(new CreditoConSaldoMoraDTO(
                            credito.getId(),
                            credito.getCliente().getNombreCompleto(),
                            credito.getEstado().name(),
                            mora.saldo()
                    ));
                }
            }

            // Vigentes y mora solo aplican a cartera activa: un crédito PAGADO
            // no está vigente y no tiene sentido evaluarle cuotas vencidas.
            if (!esActivo) continue;

            creditosVigentes++;

            List<Integer> cuotasVencidas = cronogramaService.cuotasVencidasSinPagar(credito, hoy);
            List<CronogramaService.FilaCronograma> cronograma = cronogramaService.generarCronograma(credito);

            // Aging y PAR usan las mismas cuotas vencidas que "En mora", así los números cuadran.
            riesgo.agregar(credito.getMontoSolicitado(), cronograma, cuotasVencidas);

            if (!cuotasVencidas.isEmpty()) {
                creditosEnMora++;
                // Lo realmente vencido: el saldo de cada cuota vencida, descontando lo ya abonado
                // (mismo criterio que el reporte de mora). Antes era la cuota completa x cuotas vencidas,
                // que inflaba el monto cuando una cuota tenía un abono parcial.
                BigDecimal montoMoraCredito = cronograma.stream()
                        .filter(fila -> cuotasVencidas.contains(fila.numeroCuota()))
                        .map(CronogramaService.FilaCronograma::saldoPendienteCuota)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                totalEnMora = totalEnMora.add(montoMoraCredito);

                detalleMora.add(new CreditoEnMoraDTO(
                        credito.getId(),
                        credito.getCliente().getNombreCompleto(),
                        montoMoraCredito,
                        cuotasVencidas.size(),
                        mora != null ? mora.saldo() : BigDecimal.ZERO
                ));
            }
        }

        // Los que más deben, primero
        detalleSaldoMora.sort(Comparator.comparing(CreditoConSaldoMoraDTO::saldoMora).reversed());

        return new DashboardResponse(totalPrestado, totalCobrado, totalEnMora,
                creditosVigentes, creditosEnMora, detalleMora,
                moraDevengada, moraCobrada, moraCondonada, saldoMora,
                detalleSaldoMora.size(), detalleSaldoMora,
                riesgo.capitalVigente(), riesgo.aging(), riesgo.par());
    }
}
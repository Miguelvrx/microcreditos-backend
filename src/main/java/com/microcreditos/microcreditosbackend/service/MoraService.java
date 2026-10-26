package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.AccionAuditoria;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.MovimientoMora;
import com.microcreditos.microcreditosbackend.model.Pago;
import com.microcreditos.microcreditosbackend.model.TipoMovimientoMora;
import com.microcreditos.microcreditosbackend.repository.CreditoRepository;
import com.microcreditos.microcreditosbackend.repository.MovimientoMoraRepository;
import com.microcreditos.microcreditosbackend.repository.PagoRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Saldo de mora de un crédito y registro de cobros y condonaciones.
 *
 *   saldo = interés devengado (InteresMoratorioService) - cobrado - condonado   (nunca negativo)
 *
 * La mora se lleva APARTE de las cuotas: cobrarla o condonarla no toca los pagos, el derrame ni
 * el estado del crédito. Se puede cobrar en créditos APROBADO, EN_MORA y PAGADO (un cliente puede
 * saldar las cuotas y quedar debiendo mora). Un cobro o una condonación no pueden superar el saldo.
 * La condonación exige motivo; que solo la haga un ADMIN lo controla SecurityConfig.
 */
@Service
public class MoraService {

    private static final int MAX_MOTIVO = 300;

    private final CreditoRepository creditoRepository;
    private final PagoRepository pagoRepository;
    private final MovimientoMoraRepository movimientoMoraRepository;
    private final InteresMoratorioService interesMoratorioService;
    private final AuditoriaService auditoriaService;

    public MoraService(CreditoRepository creditoRepository,
                       PagoRepository pagoRepository,
                       MovimientoMoraRepository movimientoMoraRepository,
                       InteresMoratorioService interesMoratorioService,
                       AuditoriaService auditoriaService) {
        this.creditoRepository = creditoRepository;
        this.pagoRepository = pagoRepository;
        this.movimientoMoraRepository = movimientoMoraRepository;
        this.interesMoratorioService = interesMoratorioService;
        this.auditoriaService = auditoriaService;
    }

    public record ResumenMora(BigDecimal devengado, BigDecimal cobrado, BigDecimal condonado, BigDecimal saldo,
                              List<InteresMoratorioService.MoraCuota> cuotas, List<MovimientoMora> movimientos) {}

    // ===================== Consulta =====================

    public ResumenMora resumen(Long creditoId) {
        return resumen(creditoId, LocalDate.now());
    }

    // Las versiones con "hoy" existen para poder probar con fechas fijas.
    ResumenMora resumen(Long creditoId, LocalDate hoy) {
        return armarResumen(obtenerCredito(creditoId), hoy);
    }

    // ===================== Cobro y condonación =====================

    @Transactional
    public ResumenMora cobrar(Long creditoId, BigDecimal monto) {
        return cobrar(creditoId, monto, LocalDate.now());
    }

    ResumenMora cobrar(Long creditoId, BigDecimal monto, LocalDate hoy) {
        return registrarMovimiento(creditoId, TipoMovimientoMora.COBRO, monto, null, hoy);
    }

    @Transactional
    public ResumenMora condonar(Long creditoId, BigDecimal monto, String motivo) {
        return condonar(creditoId, monto, motivo, LocalDate.now());
    }

    ResumenMora condonar(Long creditoId, BigDecimal monto, String motivo, LocalDate hoy) {
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalStateException("El motivo de la condonación es obligatorio.");
        }
        motivo = motivo.trim();
        if (motivo.length() > MAX_MOTIVO) {
            throw new IllegalStateException("El motivo no puede superar los " + MAX_MOTIVO + " caracteres.");
        }
        return registrarMovimiento(creditoId, TipoMovimientoMora.CONDONACION, monto, motivo, hoy);
    }

    private ResumenMora registrarMovimiento(Long creditoId, TipoMovimientoMora tipo, BigDecimal monto,
                                            String motivo, LocalDate hoy) {
        Credito credito = obtenerCredito(creditoId);

        if (credito.getEstado() != Credito.EstadoCredito.APROBADO
                && credito.getEstado() != Credito.EstadoCredito.EN_MORA
                && credito.getEstado() != Credito.EstadoCredito.PAGADO) {
            throw new IllegalStateException(
                    "No se puede registrar mora en un crédito en estado " + credito.getEstado() + ".");
        }
        if (monto == null) {
            throw new IllegalStateException("El monto es obligatorio.");
        }
        monto = monto.setScale(2, RoundingMode.HALF_UP);
        if (monto.signum() <= 0) {
            throw new IllegalStateException("El monto debe ser mayor a 0.");
        }

        ResumenMora antes = armarResumen(credito, hoy);
        if (monto.compareTo(antes.saldo()) > 0) {
            throw new IllegalStateException(
                    "El monto (" + monto + ") supera el saldo de mora del crédito (" + antes.saldo() + ").");
        }

        MovimientoMora movimiento = new MovimientoMora();
        movimiento.setCreditoId(creditoId);
        movimiento.setTipo(tipo);
        movimiento.setMonto(monto);
        movimiento.setFecha(hoy);
        movimiento.setMotivo(motivo);
        movimiento.setUsuario(usuarioActual());
        movimiento.setCreadoEn(LocalDateTime.now());
        movimientoMoraRepository.save(movimiento);

        if (tipo == TipoMovimientoMora.COBRO) {
            auditoriaService.registrar(AccionAuditoria.MORA_COBRADA, "Credito", creditoId,
                    "Cobro de mora de " + monto + " del crédito #" + creditoId);
        } else {
            auditoriaService.registrar(AccionAuditoria.MORA_CONDONADA, "Credito", creditoId,
                    truncar("Condonación de mora de " + monto + " del crédito #" + creditoId
                            + ". Motivo: " + motivo, 500));
        }

        return armarResumen(credito, hoy);
    }

    // ===================== Helpers =====================

    private ResumenMora armarResumen(Credito credito, LocalDate hoy) {
        List<Pago> pagos = pagoRepository.findByCreditoId(credito.getId());
        InteresMoratorioService.MoraCredito mora = interesMoratorioService.calcular(credito, pagos, hoy);
        List<MovimientoMora> movimientos = movimientoMoraRepository.findByCreditoIdOrderByIdDesc(credito.getId());

        BigDecimal cobrado = sumar(movimientos, TipoMovimientoMora.COBRO);
        BigDecimal condonado = sumar(movimientos, TipoMovimientoMora.CONDONACION);
        BigDecimal saldo = mora.total().subtract(cobrado).subtract(condonado).max(BigDecimal.ZERO);

        return new ResumenMora(mora.total(), cobrado, condonado, saldo, mora.cuotas(), movimientos);
    }

    private BigDecimal sumar(List<MovimientoMora> movimientos, TipoMovimientoMora tipo) {
        return movimientos.stream()
                .filter(m -> m.getTipo() == tipo)
                .map(MovimientoMora::getMonto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Credito obtenerCredito(Long creditoId) {
        return creditoRepository.findById(creditoId)
                .orElseThrow(() -> new NoSuchElementException("Crédito no encontrado con id: " + creditoId));
    }

    private String usuarioActual() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        return (autenticacion != null && autenticacion.getName() != null) ? autenticacion.getName() : "SISTEMA";
    }

    private String truncar(String texto, int max) {
        return texto.length() <= max ? texto : texto.substring(0, max - 3) + "...";
    }
}
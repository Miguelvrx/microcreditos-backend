package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.AccionAuditoria;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.MetodoPago;
import com.microcreditos.microcreditosbackend.model.Pago;
import com.microcreditos.microcreditosbackend.model.TipoNotificacion;
import com.microcreditos.microcreditosbackend.repository.CreditoRepository;
import com.microcreditos.microcreditosbackend.repository.PagoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PagoService {

    /** Largo máximo de la referencia de pago (coincide con la columna referencia_pago de V4). */
    public static final int MAX_REFERENCIA = 50;

    @Autowired
    private PagoRepository pagoRepository;

    @Autowired
    private CreditoRepository creditoRepository;

    @Autowired
    private AuditoriaService auditoriaService;

    @Autowired
    private CronogramaService cronogramaService;

    @Autowired
    private NotificacionService notificacionService;   // aviso si una anulación deja el crédito en mora

    public List<Pago> listarPorCredito(Long creditoId) {
        return pagoRepository.findByCreditoId(creditoId);
    }

    /**
     * Ids de los créditos que ya tienen un abono ACTIVO con esa referencia. Se usa para avisar
     * de un posible duplicado (la misma transferencia capturada dos veces); no bloquea nada.
     * Los abonos anulados no cuentan: al anular un abono, sus filas de reverso heredan la misma
     * referencia, así que se descartan junto con las filas que revierten.
     */
    @Transactional(readOnly = true)
    public List<Long> creditosConReferencia(String referencia) {
        String ref = limpiarReferencia(referencia);
        if (ref == null) {
            return List.of();
        }

        List<Pago> coincidencias = pagoRepository.findByReferenciaPago(ref);
        Set<Long> anulados = coincidencias.stream()
                .map(Pago::getPagoOriginalId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return coincidencias.stream()
                .filter(p -> p.getEstado() != Pago.EstadoPago.ANULACION && !anulados.contains(p.getId()))
                .map(p -> p.getCredito().getId())
                .distinct()
                .sorted()
                .toList();
    }

    /** Abono en efectivo y sin referencia (el caso más común). */
    @Transactional
    public List<Pago> registrarPago(Long creditoId, Integer numeroCuota, BigDecimal montoPagado) {
        return registrarPago(creditoId, numeroCuota, montoPagado, MetodoPago.EFECTIVO, null);
    }

    @Transactional
    public List<Pago> registrarPago(Long creditoId, Integer numeroCuota, BigDecimal montoPagado,
                                    MetodoPago metodoPago, String referenciaPago) {
        Credito credito = creditoRepository.findById(creditoId)
                .orElseThrow(() -> new RuntimeException("Crédito no encontrado con id: " + creditoId));

        if (credito.getEstado() != Credito.EstadoCredito.APROBADO
                && credito.getEstado() != Credito.EstadoCredito.EN_MORA) {
            throw new IllegalStateException(
                    "No se pueden registrar pagos en un crédito en estado " + credito.getEstado()
                    + ". Solo se aceptan pagos en créditos APROBADO o EN_MORA.");
        }

        montoPagado = montoPagado.setScale(2, RoundingMode.HALF_UP);

        if (montoPagado.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("El monto abonado debe ser mayor a 0.");
        }

        // Método y referencia: sin método se asume EFECTIVO. Transferencia y depósito exigen referencia.
        MetodoPago metodo = metodoPago != null ? metodoPago : MetodoPago.EFECTIVO;
        String referencia = limpiarReferencia(referenciaPago);

        if (metodo.exigeReferencia() && referencia == null) {
            throw new IllegalStateException(
                    "La referencia es obligatoria cuando el método es " + metodo.etiqueta().toLowerCase(Locale.ROOT)
                    + " (folio, clave de rastreo o número de ficha).");
        }
        if (referencia != null && referencia.length() > MAX_REFERENCIA) {
            throw new IllegalStateException(
                    "La referencia no puede superar los " + MAX_REFERENCIA + " caracteres.");
        }

        List<Pago> pagosExistentes = pagoRepository.findByCreditoId(creditoId);

        // Construye la lista de cuotas pendientes (saldo > 0) desde la primera
        // incompleta hasta la última del plazo, con el saldo faltante de cada una.
        // Las anulaciones (monto negativo) restan del acumulado, así que una cuota
        // cuyo abono fue anulado vuelve a aparecer como pendiente.
        List<Integer> cuotasPendientes = new ArrayList<>();
        Map<Integer, BigDecimal> saldoPorCuota = new LinkedHashMap<>();
        for (int cuota = 1; cuota <= credito.getPlazoMeses(); cuota++) {
            final int cuotaEvaluada = cuota;
            BigDecimal acumulado = pagosExistentes.stream()
                    .filter(p -> p.getNumeroCuota().equals(cuotaEvaluada))
                    .map(Pago::getMontoPagado)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal saldo = credito.getCuotaMensual().subtract(acumulado);
            if (saldo.compareTo(BigDecimal.ZERO) > 0) {
                cuotasPendientes.add(cuota);
                saldoPorCuota.put(cuota, saldo);
            }
        }

        if (cuotasPendientes.isEmpty()) {
            throw new IllegalStateException("El crédito ya tiene todas sus cuotas pagadas.");
        }

        int siguienteCuotaEsperada = cuotasPendientes.get(0);
        if (!numeroCuota.equals(siguienteCuotaEsperada)) {
            throw new IllegalStateException(
                    "Debes completar primero el pago de la cuota " + siguienteCuotaEsperada + ".");
        }

        BigDecimal saldoPendienteTotal = cuotasPendientes.stream()
                .map(saldoPorCuota::get)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (montoPagado.compareTo(saldoPendienteTotal) > 0) {
            throw new IllegalStateException(
                    "El monto abonado (" + montoPagado + ") supera el saldo total pendiente del crédito ("
                            + saldoPendienteTotal + ").");
        }

        // Derrame: reparte el abono en cascada entre las cuotas pendientes.
        // Todas las filas de este abono comparten el mismo abonoRef (para poder anularlas
        // juntas después) y también el mismo método y referencia: es un solo movimiento de dinero.
        BigDecimal restante = montoPagado;
        LocalDate hoy = LocalDate.now();
        String abonoRef = UUID.randomUUID().toString();
        List<Pago> pagosCreados = new ArrayList<>();

        // El detalle de auditoría solo menciona el método cuando no es el caso común (efectivo sin referencia).
        String detalleMetodo = (metodo == MetodoPago.EFECTIVO && referencia == null)
                ? ""
                : " (" + metodo.name() + (referencia != null ? ", ref. " + referencia : "") + ")";

        for (Integer cuota : cuotasPendientes) {
            if (restante.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal saldoCuota = saldoPorCuota.get(cuota);
            BigDecimal aplicado = restante.min(saldoCuota);
            LocalDate fechaVencimientoCuota = credito.getFechaInicio().plusMonths(cuota);

            Pago pago = new Pago();
            pago.setCredito(credito);
            pago.setNumeroCuota(cuota);
            pago.setMontoPagado(aplicado);
            pago.setFechaVencimiento(fechaVencimientoCuota);
            pago.setFechaPago(hoy);
            pago.setAbonoRef(abonoRef);
            pago.setMetodoPago(metodo);
            pago.setReferenciaPago(referencia);
            pago.setEstado(hoy.isAfter(fechaVencimientoCuota) ? Pago.EstadoPago.ATRASADO : Pago.EstadoPago.PAGADO);

            pagosCreados.add(pagoRepository.save(pago));

            auditoriaService.registrar(
                    AccionAuditoria.PAGO_REGISTRADO,
                    "Pago",
                    pagosCreados.get(pagosCreados.size() - 1).getId(),
                    "Abono de " + aplicado + " a la cuota " + cuota + " del crédito #" + creditoId + detalleMetodo);

            restante = restante.subtract(aplicado);
        }

        // Si el derrame tocó más de una cuota, deja una fila resumen además de las individuales.
        if (pagosCreados.size() > 1) {
            int primeraCuota = pagosCreados.get(0).getNumeroCuota();
            int ultimaCuotaTocada = pagosCreados.get(pagosCreados.size() - 1).getNumeroCuota();
            auditoriaService.registrar(
                    AccionAuditoria.PAGO_REGISTRADO,
                    "Credito",
                    creditoId,
                    "Derrame aplicado: abono de " + montoPagado + " distribuido en cuotas "
                            + primeraCuota + " a " + ultimaCuotaTocada + " del crédito #" + creditoId + detalleMetodo);
        }

        // ¿Este abono saldó por completo el crédito?
        if (montoPagado.compareTo(saldoPendienteTotal) == 0) {
            credito.setEstado(Credito.EstadoCredito.PAGADO);
            creditoRepository.save(credito);

            auditoriaService.registrar(
                    AccionAuditoria.CREDITO_PAGADO,
                    "Credito",
                    creditoId,
                    "Crédito #" + creditoId + " saldado con el abono registrado.");

        } else if (credito.getEstado() == Credito.EstadoCredito.EN_MORA) {
            // Lista explícita (pagos existentes + los recién creados) en vez de leer
            // credito.getPagos(), que puede estar desactualizada dentro de la transacción
            // (mismo criterio que anularAbono).
            List<Pago> pagosActualizados = new ArrayList<>(pagosExistentes);
            pagosActualizados.addAll(pagosCreados);

            if (!cronogramaService.estaEnMora(credito, pagosActualizados, hoy)) {
                credito.setEstado(Credito.EstadoCredito.APROBADO);
                creditoRepository.save(credito);

                auditoriaService.registrar(
                        AccionAuditoria.CREDITO_AL_DIA,
                        "Credito",
                        creditoId,
                        "Crédito #" + creditoId + " volvió a APROBADO: el cliente se puso al día con sus cuotas vencidas.");
            }
        }

        return pagosCreados;
    }

    /**
     * Anula un abono completo mediante contra-asiento: inserta una fila
     * ANULACION con monto negativo por cada fila del abono original. No borra
     * ni edita nada, así que el historial y la auditoría quedan íntegros.
     * Cada fila de anulación hereda el método y la referencia del pago que revierte.
     *
     * Reglas:
     *  - Motivo obligatorio (máx. 300 caracteres).
     *  - Solo se puede anular el ÚLTIMO abono activo del crédito (respeta el
     *    orden FIFO: no pueden quedar cuotas posteriores pagadas con anteriores abiertas).
     *  - El pago indicado puede ser cualquiera de las filas del abono; se anulan todas.
     *  - Si el crédito estaba PAGADO, se reabre (APROBADO, o EN_MORA si ya
     *    tiene cuotas vencidas sin cubrir). Si estaba APROBADO y la anulación
     *    lo deja con cuotas vencidas sin cubrir, pasa a EN_MORA de inmediato.
     */
    @Transactional
    public List<Pago> anularAbono(Long pagoId, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalStateException("El motivo de la anulación es obligatorio.");
        }
        motivo = motivo.trim();
        if (motivo.length() > 300) {
            throw new IllegalStateException("El motivo no puede superar los 300 caracteres.");
        }

        Pago pagoIndicado = pagoRepository.findById(pagoId)
                .orElseThrow(() -> new NoSuchElementException("Pago no encontrado con id: " + pagoId));

        if (pagoIndicado.getEstado() == Pago.EstadoPago.ANULACION) {
            throw new IllegalStateException("Ese registro ya es una anulación; no se puede anular de nuevo.");
        }

        Credito credito = pagoIndicado.getCredito();
        Long creditoId = credito.getId();

        if (credito.getEstado() != Credito.EstadoCredito.APROBADO
                && credito.getEstado() != Credito.EstadoCredito.EN_MORA
                && credito.getEstado() != Credito.EstadoCredito.PAGADO) {
            throw new IllegalStateException(
                    "No se pueden anular pagos de un crédito en estado " + credito.getEstado() + ".");
        }

        List<Pago> todos = pagoRepository.findByCreditoId(creditoId);

        Set<Long> yaAnulados = todos.stream()
                .map(Pago::getPagoOriginalId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (yaAnulados.contains(pagoId)) {
            throw new IllegalStateException("Ese pago ya fue anulado.");
        }

        // Pagos "activos": ni son anulaciones ni fueron anulados.
        List<Pago> activos = todos.stream()
                .filter(p -> p.getEstado() != Pago.EstadoPago.ANULACION && !yaAnulados.contains(p.getId()))
                .toList();

        Pago ultimo = activos.stream()
                .max(Comparator.comparing(Pago::getId))
                .orElseThrow(() -> new IllegalStateException("El crédito no tiene abonos activos para anular."));

        String claveUltimoAbono = claveAbono(ultimo);
        List<Pago> abonoAnular = activos.stream()
                .filter(p -> claveAbono(p).equals(claveUltimoAbono))
                .sorted(Comparator.comparing(Pago::getId))
                .toList();

        boolean pertenece = abonoAnular.stream().anyMatch(p -> p.getId().equals(pagoId));
        if (!pertenece) {
            throw new IllegalStateException(
                    "Solo se puede anular el último abono registrado del crédito. "
                    + "Anula primero los abonos posteriores.");
        }

        // Contra-asientos: una fila negativa por cada fila del abono original.
        LocalDate hoy = LocalDate.now();
        List<Pago> reversos = new ArrayList<>();
        BigDecimal montoTotal = BigDecimal.ZERO;

        for (Pago original : abonoAnular) {
            Pago reverso = new Pago();
            reverso.setCredito(credito);
            reverso.setNumeroCuota(original.getNumeroCuota());
            reverso.setMontoPagado(original.getMontoPagado().negate());
            reverso.setFechaPago(hoy);
            reverso.setFechaVencimiento(original.getFechaVencimiento());
            reverso.setEstado(Pago.EstadoPago.ANULACION);
            reverso.setAbonoRef(original.getAbonoRef());
            reverso.setPagoOriginalId(original.getId());
            reverso.setMotivoAnulacion(motivo);
            reverso.setMetodoPago(original.getMetodoPago());
            reverso.setReferenciaPago(original.getReferenciaPago());

            reversos.add(pagoRepository.save(reverso));
            montoTotal = montoTotal.add(original.getMontoPagado());
        }

        int primeraCuota = abonoAnular.get(0).getNumeroCuota();
        int ultimaCuota = abonoAnular.get(abonoAnular.size() - 1).getNumeroCuota();
        String rangoCuotas = (primeraCuota == ultimaCuota)
                ? "cuota " + primeraCuota
                : "cuotas " + primeraCuota + " a " + ultimaCuota;

        auditoriaService.registrar(
                AccionAuditoria.PAGO_ANULADO,
                "Pago",
                pagoId,
                truncar("Anulado abono de " + montoTotal + " (" + rangoCuotas + ") del crédito #"
                        + creditoId + ". Motivo: " + motivo, 500));

        // Reevaluar el estado del crédito. Se vuelve a consultar la lista de
        // pagos (ya incluye los contra-asientos) en vez de usar credito.getPagos(),
        // que puede estar desactualizada dentro de esta transacción.
        List<Pago> pagosActualizados = pagoRepository.findByCreditoId(creditoId);
        boolean estadoCambio = false;

        if (credito.getEstado() == Credito.EstadoCredito.PAGADO) {
            credito.setEstado(Credito.EstadoCredito.APROBADO);
            estadoCambio = true;

            auditoriaService.registrar(
                    AccionAuditoria.CREDITO_REABIERTO,
                    "Credito",
                    creditoId,
                    "Crédito #" + creditoId + " reabierto: se anuló un abono que lo había saldado.");
        }

        if (credito.getEstado() == Credito.EstadoCredito.APROBADO
                && cronogramaService.estaEnMora(credito, pagosActualizados, hoy)) {
            credito.setEstado(Credito.EstadoCredito.EN_MORA);
            estadoCambio = true;

            auditoriaService.registrar(
                    AccionAuditoria.CREDITO_EN_MORA,
                    "Credito",
                    creditoId,
                    "Pasó a EN_MORA: al anular un abono quedaron cuotas vencidas sin cubrir.");

            notificacionService.crear(
                    TipoNotificacion.CREDITO_EN_MORA,
                    creditoId,
                    "El crédito #" + creditoId + " de " + credito.getCliente().getNombreCompleto()
                            + " pasó a mora tras anularse un abono.");
        }

        if (estadoCambio) {
            creditoRepository.save(credito);
        }

        return reversos;
    }

    // Agrupa las filas de un mismo abono. Los pagos anteriores a V2 no tienen
    // abonoRef, así que cada uno cuenta como un abono de una sola fila.
    private String claveAbono(Pago pago) {
        return pago.getAbonoRef() != null ? pago.getAbonoRef() : "sin-ref-" + pago.getId();
    }

    // Quita espacios de los extremos y pasa a mayúsculas, para que "abc 1 " y "ABC 1" cuenten como
    // la misma referencia al buscar duplicados. Una referencia vacía equivale a no tener referencia.
    private String limpiarReferencia(String referencia) {
        if (referencia == null) {
            return null;
        }
        String limpia = referencia.trim().toUpperCase(Locale.ROOT);
        return limpia.isEmpty() ? null : limpia;
    }

    private String truncar(String texto, int max) {
        return texto.length() <= max ? texto : texto.substring(0, max - 3) + "...";
    }
}
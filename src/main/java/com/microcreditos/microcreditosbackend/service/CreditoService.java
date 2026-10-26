package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.AccionAuditoria;
import com.microcreditos.microcreditosbackend.model.Cliente;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.repository.ClienteRepository;
import com.microcreditos.microcreditosbackend.repository.CreditoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.microcreditos.microcreditosbackend.model.RolUsuario;
import com.microcreditos.microcreditosbackend.model.Usuario;
import com.microcreditos.microcreditosbackend.repository.UsuarioRepository;


import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.NoSuchElementException;
import java.util.Objects;

@Service
public class CreditoService {

    @Autowired
    private CreditoRepository creditoRepository;

    @Autowired
    private ClienteRepository clienteRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private AuditoriaService auditoriaService;

    public List<Credito> listarTodos() {
        return creditoRepository.findAll();
    }

    public Optional<Credito> buscarPorId(Long id) {
        return creditoRepository.findById(id);
    }

    public List<Credito> buscarPorCliente(Long clienteId) {
        return creditoRepository.findByClienteId(clienteId);
    }


    @Transactional
    public Credito aprobar(Long id) {
        Credito credito = creditoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Crédito no encontrado con id: " + id));

        if (credito.getEstado() != Credito.EstadoCredito.PENDIENTE) {
            throw new IllegalStateException(
                    "Solo se puede aprobar un crédito en estado PENDIENTE. Estado actual: " + credito.getEstado());
        }

        credito.setEstado(Credito.EstadoCredito.APROBADO);
        Credito guardado = creditoRepository.save(credito);

        auditoriaService.registrar(
                AccionAuditoria.CREDITO_APROBADO,
                "Credito",
                guardado.getId(),
                "Crédito aprobado. Cliente: " + guardado.getCliente().getNombreCompleto()
                        + ", monto: " + guardado.getMontoSolicitado());

        return guardado;
    }

    @Transactional
    public Credito rechazar(Long id) {
        Credito credito = creditoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Crédito no encontrado con id: " + id));

        if (credito.getEstado() != Credito.EstadoCredito.PENDIENTE) {
            throw new IllegalStateException(
                    "Solo se puede rechazar un crédito en estado PENDIENTE. Estado actual: " + credito.getEstado());
        }

        credito.setEstado(Credito.EstadoCredito.RECHAZADO);
        Credito guardado = creditoRepository.save(credito);

        auditoriaService.registrar(
                AccionAuditoria.CREDITO_RECHAZADO,
                "Credito",
                guardado.getId(),
                "Crédito rechazado. Cliente: " + guardado.getCliente().getNombreCompleto()
                        + ", monto: " + guardado.getMontoSolicitado());

        return guardado;
    }


        @Transactional
        public Credito asignarCobrador(Long creditoId, Long cobradorId) {
        Credito credito = creditoRepository.findById(creditoId)
                .orElseThrow(() -> new NoSuchElementException("Crédito no encontrado con id: " + creditoId));

        Usuario nuevo = null;
        if (cobradorId != null) {
            nuevo = usuarioRepository.findById(cobradorId)
                    .orElseThrow(() -> new NoSuchElementException("Usuario no encontrado con id: " + cobradorId));

            if (nuevo.getRol() != RolUsuario.COBRADOR) {
                throw new IllegalStateException(
                        "El usuario " + nuevo.getNombreUsuario() + " no tiene rol COBRADOR.");
            }
            if (!nuevo.isActivo()) {
                throw new IllegalStateException(
                        "El usuario " + nuevo.getNombreUsuario() + " está desactivado.");
            }
        }

        Usuario anterior = credito.getCobrador();
        Long idAnterior = anterior != null ? anterior.getId() : null;
        if (Objects.equals(idAnterior, cobradorId)) {
            return credito;   // ya estaba asignado así: nada que guardar ni auditar
        }

        credito.setCobrador(nuevo);
        Credito guardado = creditoRepository.save(credito);

        auditoriaService.registrar(
                AccionAuditoria.COBRADOR_ASIGNADO,
                "Credito",
                creditoId,
                "Cobrador del crédito #" + creditoId + ": de "
                        + (anterior != null ? anterior.getNombreUsuario() : "sin cobrador")
                        + " a " + (nuevo != null ? nuevo.getNombreUsuario() : "sin cobrador"));

        return guardado;
    }

    /**
     * Crea un crédito calculando automáticamente la cuota mensual
     * usando el sistema de amortización francesa (cuota fija).
     */
    @Transactional
    public Credito crearCredito(Long clienteId, BigDecimal montoSolicitado,
                                BigDecimal tasaInteresMensual, Integer plazoMeses) {

        Cliente cliente = clienteRepository.findById(clienteId)
                .orElseThrow(() -> new RuntimeException("Cliente no encontrado con id: " + clienteId));

        BigDecimal cuota = calcularCuotaMensual(montoSolicitado, tasaInteresMensual, plazoMeses);

        Credito credito = new Credito();
        credito.setCliente(cliente);
        credito.setMontoSolicitado(montoSolicitado);
        credito.setTasaInteresMensual(tasaInteresMensual);
        credito.setPlazoMeses(plazoMeses);
        credito.setCuotaMensual(cuota);
        credito.setFechaInicio(java.time.LocalDate.now());
        credito.setEstado(Credito.EstadoCredito.PENDIENTE);

        Credito guardado = creditoRepository.save(credito);

        auditoriaService.registrar(
                AccionAuditoria.CREDITO_CREADO,
                "Credito",
                guardado.getId(),
                "Crédito creado. Cliente: " + cliente.getNombreCompleto()
                        + ", monto: " + montoSolicitado
                        + ", plazo: " + plazoMeses + " meses");

        return guardado;
    }

    /**
     * Fórmula de amortización francesa:
     * Cuota = Monto * [ i * (1+i)^n ] / [ (1+i)^n - 1 ]
     * donde i = tasa mensual en decimal (ej: 2.5% -> 0.025)
     */
    public BigDecimal calcularCuotaMensual(BigDecimal monto, BigDecimal tasaMensualPorcentaje, Integer plazoMeses) {
        BigDecimal i = tasaMensualPorcentaje.divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);
        BigDecimal unoMasI = BigDecimal.ONE.add(i);

        BigDecimal unoMasIElevadoN = unoMasI.pow(plazoMeses, MathContext.DECIMAL64);

        BigDecimal numerador = monto.multiply(i).multiply(unoMasIElevadoN);
        BigDecimal denominador = unoMasIElevadoN.subtract(BigDecimal.ONE);

        return numerador.divide(denominador, 2, RoundingMode.HALF_UP);
    }

    @Transactional
    public void eliminar(Long id) {
    Credito credito = creditoRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Crédito no encontrado con id: " + id));

    if (credito.getEstado() != Credito.EstadoCredito.PENDIENTE
            && credito.getEstado() != Credito.EstadoCredito.RECHAZADO) {
        throw new IllegalStateException(
                "Solo se puede eliminar un crédito en estado PENDIENTE o RECHAZADO. Estado actual: " + credito.getEstado());
    }

    if (credito.getPagos() != null && !credito.getPagos().isEmpty()) {
        throw new IllegalStateException(
                "No se puede eliminar un crédito con pagos registrados.");
    }

    // Se captura antes del borrado: después de deleteById() el objeto ya no existe.
    String clienteNombre = credito.getCliente().getNombreCompleto();
    BigDecimal monto = credito.getMontoSolicitado();
    Credito.EstadoCredito estadoAnterior = credito.getEstado();

    creditoRepository.deleteById(id);

    auditoriaService.registrar(
            AccionAuditoria.CREDITO_ELIMINADO,
            "Credito",
            id,
            "Crédito eliminado. Cliente: " + clienteNombre
                    + ", monto: " + monto
                    + ", estado antes de eliminar: " + estadoAnterior);
    }
}
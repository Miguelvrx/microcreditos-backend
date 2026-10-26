package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.Notificacion;
import com.microcreditos.microcreditosbackend.model.TipoNotificacion;
import com.microcreditos.microcreditosbackend.repository.NotificacionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificacionService {

    private static final int MAX_MENSAJE = 500;

    private final NotificacionRepository notificacionRepository;

    public NotificacionService(NotificacionRepository notificacionRepository) {
        this.notificacionRepository = notificacionRepository;
    }

    // Se mantiene igual para CREDITO_EN_MORA (numeroCuota no aplica).
    @Transactional
    public Notificacion crear(TipoNotificacion tipo, Long creditoId, String mensaje) {
        return crear(tipo, creditoId, null, mensaje);
    }

    // Overload usado por CUOTA_PROXIMA_A_VENCER, que sí necesita numeroCuota
    // para poder distinguir de cuál cuota se trata y no duplicar el aviso.
    @Transactional
    public Notificacion crear(TipoNotificacion tipo, Long creditoId, Integer numeroCuota, String mensaje) {
        Notificacion n = new Notificacion();
        n.setTipo(tipo);
        n.setCreditoId(creditoId);
        n.setNumeroCuota(numeroCuota);
        n.setMensaje(mensaje.length() <= MAX_MENSAJE ? mensaje : mensaje.substring(0, MAX_MENSAJE));
        n.setFecha(LocalDateTime.now());
        n.setLeida(false);
        return notificacionRepository.save(n);
    }

    @Transactional(readOnly = true)
    public boolean existeRecordatorioCuota(Long creditoId, Integer numeroCuota) {
        return notificacionRepository.existsByTipoAndCreditoIdAndNumeroCuota(
                TipoNotificacion.CUOTA_PROXIMA_A_VENCER, creditoId, numeroCuota);
    }

    @Transactional(readOnly = true)
    public List<Notificacion> listarRecientes() {
        return notificacionRepository.findTop50ByOrderByFechaDescIdDesc();
    }

    @Transactional(readOnly = true)
    public long contarNoLeidas() {
        return notificacionRepository.countByLeidaFalse();
    }

    @Transactional
    public Notificacion marcarComoLeida(Long id) {
        Notificacion n = notificacionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Notificación no encontrada con id: " + id));
        n.setLeida(true);
        return notificacionRepository.save(n);
    }

    @Transactional
    public int marcarTodasComoLeidas() {
        List<Notificacion> pendientes = notificacionRepository.findByLeidaFalse();
        pendientes.forEach(n -> n.setLeida(true));
        notificacionRepository.saveAll(pendientes);
        return pendientes.size();
    }
}
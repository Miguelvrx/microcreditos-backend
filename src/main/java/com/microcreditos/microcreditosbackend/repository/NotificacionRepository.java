package com.microcreditos.microcreditosbackend.repository;

import com.microcreditos.microcreditosbackend.model.Notificacion;
import com.microcreditos.microcreditosbackend.model.TipoNotificacion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificacionRepository extends JpaRepository<Notificacion, Long> {

    List<Notificacion> findTop50ByOrderByFechaDescIdDesc();

    long countByLeidaFalse();

    List<Notificacion> findByLeidaFalse();

    // Usado por RecordatorioSchedulerService para no duplicar el aviso de
    // una misma cuota mientras siga dentro de la ventana de anticipación.
    boolean existsByTipoAndCreditoIdAndNumeroCuota(TipoNotificacion tipo, Long creditoId, Integer numeroCuota);
}
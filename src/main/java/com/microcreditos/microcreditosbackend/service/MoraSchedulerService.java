package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.AccionAuditoria;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.TipoNotificacion;
import com.microcreditos.microcreditosbackend.repository.CreditoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.LocalDate;
import java.util.List;

@Service
public class MoraSchedulerService {

    @Autowired
    private CreditoRepository creditoRepository;

    @Autowired
    private CronogramaService cronogramaService;

    @Autowired
    private AuditoriaService auditoriaService;

    @Autowired
    private NotificacionService notificacionService;

    // Si es true, el job también corre al arrancar el backend (ver actualizarAlIniciar).
    // Por defecto es false para que no se dispare en las pruebas automáticas.
    @Value("${mora.actualizar-al-iniciar:false}")
    private boolean actualizarAlIniciar;

    // Corre todos los días a la 1:00 AM. Pasa a EN_MORA los créditos
    // APROBADOS que tengan al menos una cuota vencida sin pagar, y regresa
    // a APROBADO los créditos EN_MORA que ya no tengan cuotas vencidas
    // (red de seguridad: el caso normal ya lo resuelve PagoService.registrarPago()
    // en el momento del pago; este job cubre cualquier caso que no haya pasado
    // por ahí, por ejemplo una corrección manual de datos).
    // Es @Transactional: los cambios de estado, sus registros de auditoría y
    // las notificaciones se confirman juntos. Como corre sin sesión de usuario,
    // la auditoría queda a nombre de "SISTEMA".
    // La notificación se crea solo cuando el crédito CAMBIA a EN_MORA, así que
    // no se repite cada noche por el mismo crédito.
    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void actualizarEstadosDeMora() {
        LocalDate hoy = LocalDate.now();

        List<Credito> creditosAprobados = creditoRepository.findAll().stream()
                .filter(c -> c.getEstado() == Credito.EstadoCredito.APROBADO)
                .toList();

        for (Credito credito : creditosAprobados) {
            if (cronogramaService.estaEnMora(credito, hoy)) {
                credito.setEstado(Credito.EstadoCredito.EN_MORA);
                creditoRepository.save(credito);

                auditoriaService.registrar(
                        AccionAuditoria.CREDITO_EN_MORA,
                        "Credito",
                        credito.getId(),
                        "Pasó a EN_MORA por cuotas vencidas sin pagar (proceso automático)");

                notificacionService.crear(
                        TipoNotificacion.CREDITO_EN_MORA,
                        credito.getId(),
                        "El crédito #" + credito.getId() + " de "
                                + credito.getCliente().getNombreCompleto()
                                + " pasó a mora por cuotas vencidas sin pagar.");
            }
        }

        List<Credito> creditosEnMora = creditoRepository.findAll().stream()
                .filter(c -> c.getEstado() == Credito.EstadoCredito.EN_MORA)
                .toList();

        for (Credito credito : creditosEnMora) {
            if (!cronogramaService.estaEnMora(credito, hoy)) {
                credito.setEstado(Credito.EstadoCredito.APROBADO);
                creditoRepository.save(credito);

                auditoriaService.registrar(
                        AccionAuditoria.CREDITO_AL_DIA,
                        "Credito",
                        credito.getId(),
                        "Volvió a APROBADO: ya no tiene cuotas vencidas sin pagar (proceso automático)");
            }
        }
    }


    // Recuperación al arrancar: Spring no repite un cron que se perdió porque el
    // backend estaba apagado a la 1:00 AM. Al iniciar, se ejecuta la misma revisión.
    // Es seguro repetirla: la notificación y la auditoría solo se crean cuando el
    // crédito CAMBIA de estado. Lleva su propio @Transactional porque llamar a
    // actualizarEstadosDeMora() desde esta misma clase no pasa por el proxy de Spring.
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void actualizarAlIniciar() {
        if (!actualizarAlIniciar) {
            return;
        }
        actualizarEstadosDeMora();
    }
}
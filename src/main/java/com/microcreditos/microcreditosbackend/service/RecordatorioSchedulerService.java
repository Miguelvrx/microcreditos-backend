package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.TipoNotificacion;
import com.microcreditos.microcreditosbackend.repository.CreditoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class RecordatorioSchedulerService {

    // Días de anticipación con los que se avisa antes del vencimiento de
    // una cuota. Solo cambiar este número si se decide ajustar la ventana.
    private static final int DIAS_ANTICIPACION = 3;

    @Autowired
    private CreditoRepository creditoRepository;

    @Autowired
    private CronogramaService cronogramaService;

    @Autowired
    private NotificacionService notificacionService;

    // Corre todos los días a la 1:30 AM, 30 minutos después de
    // MoraSchedulerService, para que los créditos que hoy cambiaron a
    // EN_MORA ya no entren en este cálculo (ver el filtro por APROBADO).
    // Solo avisa cuotas PENDIENTE o PARCIAL con vencimiento entre hoy y
    // los próximos DIAS_ANTICIPACION días. No repite el aviso: antes de
    // crear la notificación, verifica que no exista ya una para ese mismo
    // crédito + número de cuota (NotificacionService.existeRecordatorioCuota).
    @Scheduled(cron = "0 30 1 * * *")
    @Transactional
    public void enviarRecordatoriosDeCuotasProximasAVencer() {
        LocalDate hoy = LocalDate.now();

        List<Credito> creditosAprobados = creditoRepository.findAll().stream()
                .filter(c -> c.getEstado() == Credito.EstadoCredito.APROBADO)
                .toList();

        for (Credito credito : creditosAprobados) {
            List<CronogramaService.FilaCronograma> cronograma = cronogramaService.generarCronograma(credito);

            for (CronogramaService.FilaCronograma fila : cronograma) {
                boolean pendiente = fila.estado() == CronogramaService.FilaCronograma.EstadoCuota.PENDIENTE
                        || fila.estado() == CronogramaService.FilaCronograma.EstadoCuota.PARCIAL;
                if (!pendiente) {
                    continue;
                }

                long diasRestantes = ChronoUnit.DAYS.between(hoy, fila.fechaVencimiento());
                if (diasRestantes < 0 || diasRestantes > DIAS_ANTICIPACION) {
                    continue;
                }

                if (notificacionService.existeRecordatorioCuota(credito.getId(), fila.numeroCuota())) {
                    continue;
                }

                String textoDias = diasRestantes == 0 ? "vence hoy"
                        : diasRestantes == 1 ? "vence mañana"
                        : "vence en " + diasRestantes + " días";

                notificacionService.crear(
                        TipoNotificacion.CUOTA_PROXIMA_A_VENCER,
                        credito.getId(),
                        fila.numeroCuota(),
                        "La cuota " + fila.numeroCuota() + " del crédito #" + credito.getId() + " de "
                                + credito.getCliente().getNombreCompleto()
                                + " " + textoDias + " (" + fila.fechaVencimiento() + ").");
            }
        }
    }
}
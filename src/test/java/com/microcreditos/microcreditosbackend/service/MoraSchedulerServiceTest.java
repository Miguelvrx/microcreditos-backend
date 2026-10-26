package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.AccionAuditoria;
import com.microcreditos.microcreditosbackend.model.Cliente;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.TipoNotificacion;
import com.microcreditos.microcreditosbackend.repository.CreditoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de MoraSchedulerService (punto 43). No usan Spring ni base
 * de datos: todas las dependencias son mocks, así que no tocan ningún dato real.
 */
@ExtendWith(MockitoExtension.class)
class MoraSchedulerServiceTest {

    @Mock
    private CreditoRepository creditoRepository;

    @Mock
    private CronogramaService cronogramaService;

    @Mock
    private AuditoriaService auditoriaService;

    @Mock
    private NotificacionService notificacionService;

    @InjectMocks
    private MoraSchedulerService servicio;

    private Credito creditoConEstado(Credito.EstadoCredito estado, Long id) {
        Credito credito = mock(Credito.class);
        when(credito.getEstado()).thenReturn(estado);
        org.mockito.Mockito.lenient().when(credito.getId()).thenReturn(id);
        return credito;
    }

    @Test
    @DisplayName("Un crédito APROBADO con cuotas vencidas pasa a EN_MORA, con auditoría de SISTEMA y notificación")
    void aprobadoConCuotasVencidasPasaAEnMora() {
        Credito credito = creditoConEstado(Credito.EstadoCredito.APROBADO, 7L);
        Cliente cliente = mock(Cliente.class);
        when(cliente.getNombreCompleto()).thenReturn("Cliente Prueba");
        when(credito.getCliente()).thenReturn(cliente);
        when(creditoRepository.findAll()).thenReturn(List.of(credito));
        when(cronogramaService.estaEnMora(eq(credito), any(LocalDate.class))).thenReturn(true);

        servicio.actualizarEstadosDeMora();

        verify(credito).setEstado(Credito.EstadoCredito.EN_MORA);
        verify(creditoRepository).save(credito);
        verify(auditoriaService).registrar(eq(AccionAuditoria.CREDITO_EN_MORA), eq("Credito"), eq(7L), anyString());
        verify(notificacionService).crear(eq(TipoNotificacion.CREDITO_EN_MORA), eq(7L), contains("#7"));
    }

    @Test
    @DisplayName("Un crédito APROBADO sin cuotas vencidas no cambia ni genera auditoría ni notificación")
    void aprobadoSinCuotasVencidasNoCambia() {
        Credito credito = creditoConEstado(Credito.EstadoCredito.APROBADO, 8L);
        when(creditoRepository.findAll()).thenReturn(List.of(credito));
        when(cronogramaService.estaEnMora(eq(credito), any(LocalDate.class))).thenReturn(false);

        servicio.actualizarEstadosDeMora();

        verify(credito, never()).setEstado(any());
        verify(creditoRepository, never()).save(any());
        verifyNoInteractions(auditoriaService, notificacionService);
    }

    @Test
    @DisplayName("Un crédito EN_MORA que ya está al día vuelve a APROBADO con auditoría CREDITO_AL_DIA y sin notificación")
    void enMoraAlDiaVuelveAAprobado() {
        Credito credito = creditoConEstado(Credito.EstadoCredito.EN_MORA, 9L);
        when(creditoRepository.findAll()).thenReturn(List.of(credito));
        when(cronogramaService.estaEnMora(eq(credito), any(LocalDate.class))).thenReturn(false);

        servicio.actualizarEstadosDeMora();

        verify(credito).setEstado(Credito.EstadoCredito.APROBADO);
        verify(creditoRepository).save(credito);
        verify(auditoriaService).registrar(eq(AccionAuditoria.CREDITO_AL_DIA), eq("Credito"), eq(9L), anyString());
        verify(notificacionService, never()).crear(any(), any(), anyString());
    }

    @Test
    @DisplayName("Un crédito que ya está EN_MORA y sigue en mora no se toca: no repite auditoría ni notificación")
    void enMoraQueSigueEnMoraNoRepiteNada() {
        Credito credito = creditoConEstado(Credito.EstadoCredito.EN_MORA, 10L);
        when(creditoRepository.findAll()).thenReturn(List.of(credito));
        when(cronogramaService.estaEnMora(eq(credito), any(LocalDate.class))).thenReturn(true);

        servicio.actualizarEstadosDeMora();

        verify(credito, never()).setEstado(any());
        verify(creditoRepository, never()).save(any());
        verifyNoInteractions(auditoriaService, notificacionService);
    }

    @Test
    @DisplayName("Con mora.actualizar-al-iniciar en false, el arranque no revisa ningún crédito")
    void alIniciarConBanderaApagadaNoHaceNada() {
        ReflectionTestUtils.setField(servicio, "actualizarAlIniciar", false);

        servicio.actualizarAlIniciar();

        verifyNoInteractions(creditoRepository, cronogramaService, auditoriaService, notificacionService);
    }

    @Test
    @DisplayName("Con mora.actualizar-al-iniciar en true, el arranque ejecuta la revisión de mora")
    void alIniciarConBanderaEncendidaEjecutaLaRevision() {
        ReflectionTestUtils.setField(servicio, "actualizarAlIniciar", true);
        when(creditoRepository.findAll()).thenReturn(List.of());

        servicio.actualizarAlIniciar();

        verify(creditoRepository, atLeastOnce()).findAll();
    }
}
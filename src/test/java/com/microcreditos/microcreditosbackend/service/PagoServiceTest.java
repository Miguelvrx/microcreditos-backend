package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.AccionAuditoria;
import com.microcreditos.microcreditosbackend.model.Cliente;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.Pago;
import com.microcreditos.microcreditosbackend.model.TipoNotificacion;
import com.microcreditos.microcreditosbackend.repository.CreditoRepository;
import com.microcreditos.microcreditosbackend.repository.PagoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Pruebas unitarias de PagoService. Los repositorios se reemplazan por un
 * "almacén" en memoria (una lista) que se comporta como la tabla pagos:
 * asigna ids al guardar y devuelve lo guardado al consultar. Así se puede
 * probar la secuencia completa pagar -> anular -> volver a pagar sin base de datos.
 *
 * CronogramaService es el real (@Spy), no un mock: la regla de mora es parte de lo probado.
 * PagoService usa LocalDate.now(), por eso las fechas de los créditos se calculan
 * relativas a "hoy" en vez de ser fijas.
 */
@ExtendWith(MockitoExtension.class)
class PagoServiceTest {

    private static final long CREDITO_ID = 15L;

    @Mock private PagoRepository pagoRepository;
    @Mock private CreditoRepository creditoRepository;
    @Mock private AuditoriaService auditoriaService;
    @Mock private NotificacionService notificacionService;
    @Spy private CronogramaService cronogramaService = new CronogramaService();
    @InjectMocks private PagoService service;

    private final List<Pago> almacen = new ArrayList<>();
    private final AtomicLong secuencia = new AtomicLong(100);
    private final LocalDate hoy = LocalDate.now();
    private Credito credito;

    @BeforeEach
    void preparar() {
        Cliente cliente = mock(Cliente.class);
        lenient().when(cliente.getNombreCompleto()).thenReturn("Cliente de Prueba");

        // $1000 a 3 meses, 2.5% mensual, cuota 350.14 (total 1050.42).
        // Inicio hace 2 meses y 5 días: las cuotas 1 y 2 ya vencieron, la 3 no.
        credito = new Credito();
        credito.setId(CREDITO_ID);
        credito.setMontoSolicitado(new BigDecimal("1000.00"));
        credito.setTasaInteresMensual(new BigDecimal("2.5"));
        credito.setPlazoMeses(3);
        credito.setCuotaMensual(new BigDecimal("350.14"));
        credito.setFechaInicio(hoy.minusMonths(2).minusDays(5));
        credito.setEstado(Credito.EstadoCredito.APROBADO);
        credito.setCliente(cliente);

        lenient().when(creditoRepository.findById(CREDITO_ID)).thenReturn(Optional.of(credito));
        lenient().when(pagoRepository.findByCreditoId(CREDITO_ID)).thenAnswer(inv -> new ArrayList<>(almacen));
        lenient().when(pagoRepository.save(any(Pago.class))).thenAnswer(inv -> {
            Pago p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(secuencia.incrementAndGet());
            }
            almacen.add(p);
            return p;
        });
        lenient().when(pagoRepository.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return almacen.stream().filter(p -> id.equals(p.getId())).findFirst();
        });
    }

    /** Siembra un pago ya existente (anterior a la prueba), sin pasar por el servicio. */
    private void sembrar(int cuota, String monto) {
        Pago p = new Pago();
        p.setId(secuencia.incrementAndGet());
        p.setCredito(credito);
        p.setNumeroCuota(cuota);
        p.setMontoPagado(new BigDecimal(monto));
        p.setEstado(Pago.EstadoPago.PAGADO);
        p.setFechaVencimiento(credito.getFechaInicio().plusMonths(cuota));
        p.setFechaPago(hoy);
        almacen.add(p);
    }

    private BigDecimal sumaPagos() {
        return almacen.stream().map(Pago::getMontoPagado).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<AccionAuditoria> accionesAuditadas() {
        ArgumentCaptor<AccionAuditoria> captor = ArgumentCaptor.forClass(AccionAuditoria.class);
        verify(auditoriaService, org.mockito.Mockito.atLeast(0)).registrar(captor.capture(), anyString(), anyLong(), anyString());
        return captor.getAllValues();
    }

    // ================================================================ registrarPago

    @Test
    void abonoExactoDeUnaCuota_creaUnSoloPagoYNoCambiaElEstado() {
        List<Pago> creados = service.registrarPago(CREDITO_ID, 1, new BigDecimal("350.14"));

        assertThat(creados).hasSize(1);
        assertThat(creados.get(0).getNumeroCuota()).isEqualTo(1);
        assertThat(creados.get(0).getMontoPagado()).isEqualByComparingTo("350.14");
        assertThat(credito.getEstado()).isEqualTo(Credito.EstadoCredito.APROBADO);
    }

    @Test
    void elMontoSeRedondeaADosDecimales() {
        List<Pago> creados = service.registrarPago(CREDITO_ID, 1, new BigDecimal("100.005"));

        assertThat(creados.get(0).getMontoPagado()).isEqualByComparingTo("100.01");
    }

    @Test
    void derrame_unAbonoQueSaldaElCredito_repartePorCuotasYLoMarcaPagado() {
        // Caso real del crédito #15: cuota 1 completa y cuota 2 con 100.00
        sembrar(1, "350.14");
        sembrar(2, "100.00");

        List<Pago> creados = service.registrarPago(CREDITO_ID, 2, new BigDecimal("600.28"));

        assertThat(creados).hasSize(2);
        assertThat(creados.get(0).getNumeroCuota()).isEqualTo(2);
        assertThat(creados.get(0).getMontoPagado()).isEqualByComparingTo("250.14");
        assertThat(creados.get(1).getNumeroCuota()).isEqualTo(3);
        assertThat(creados.get(1).getMontoPagado()).isEqualByComparingTo("350.14");
        assertThat(creados.get(0).getAbonoRef()).isNotNull().isEqualTo(creados.get(1).getAbonoRef());
        assertThat(credito.getEstado()).isEqualTo(Credito.EstadoCredito.PAGADO);
        // La cuota 2 ya había vencido: su fila queda ATRASADO; la 3 no ha vencido: PAGADO
        assertThat(creados.get(0).getEstado()).isEqualTo(Pago.EstadoPago.ATRASADO);
        assertThat(creados.get(1).getEstado()).isEqualTo(Pago.EstadoPago.PAGADO);

        assertThat(accionesAuditadas()).containsExactly(
                AccionAuditoria.PAGO_REGISTRADO,   // fila cuota 2
                AccionAuditoria.PAGO_REGISTRADO,   // fila cuota 3
                AccionAuditoria.PAGO_REGISTRADO,   // resumen del derrame
                AccionAuditoria.CREDITO_PAGADO);
    }

    @Test
    void rechazaUnAbonoQueSuperaElSaldoTotal() {
        assertThatThrownBy(() -> service.registrarPago(CREDITO_ID, 1, new BigDecimal("1050.43")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("supera el saldo total");
        assertThat(almacen).isEmpty();
    }

    @Test
    void rechazaPagarUnaCuotaQueNoEsLaPrimeraPendiente() {
        assertThatThrownBy(() -> service.registrarPago(CREDITO_ID, 2, new BigDecimal("100.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completar primero el pago de la cuota 1");
    }

    @Test
    void rechazaMontoCeroONegativo() {
        assertThatThrownBy(() -> service.registrarPago(CREDITO_ID, 1, BigDecimal.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mayor a 0");
        assertThatThrownBy(() -> service.registrarPago(CREDITO_ID, 1, new BigDecimal("-5")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rechazaPagosEnCreditosQueNoEstanAprobadosNiEnMora() {
        credito.setEstado(Credito.EstadoCredito.PAGADO);

        assertThatThrownBy(() -> service.registrarPago(CREDITO_ID, 1, new BigDecimal("10.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APROBADO o EN_MORA");
    }

    // ================================================================ anularAbono

    @Test
    void pagarSaldarYAnular_reabreElCreditoYLoDejaEnMora() {
        // Réplica automática de la prueba manual del 6 de octubre (crédito #15)
        sembrar(1, "350.14");
        sembrar(2, "100.00");
        List<Pago> abono = service.registrarPago(CREDITO_ID, 2, new BigDecimal("600.28"));
        assertThat(credito.getEstado()).isEqualTo(Credito.EstadoCredito.PAGADO);

        List<Pago> reversos = service.anularAbono(abono.get(0).getId(), "Prueba reapertura PAGADO a EN_MORA");

        assertThat(reversos).hasSize(2);
        assertThat(reversos.get(0).getMontoPagado()).isEqualByComparingTo("-250.14");
        assertThat(reversos.get(1).getMontoPagado()).isEqualByComparingTo("-350.14");
        assertThat(reversos).allMatch(r -> r.getEstado() == Pago.EstadoPago.ANULACION);
        assertThat(reversos).extracting(Pago::getPagoOriginalId)
                .containsExactly(abono.get(0).getId(), abono.get(1).getId());
        assertThat(sumaPagos()).isEqualByComparingTo("450.14");   // vuelve a lo pagado antes del abono
        assertThat(credito.getEstado()).isEqualTo(Credito.EstadoCredito.EN_MORA);

        assertThat(accionesAuditadas()).containsExactly(
                AccionAuditoria.PAGO_REGISTRADO,
                AccionAuditoria.PAGO_REGISTRADO,
                AccionAuditoria.PAGO_REGISTRADO,
                AccionAuditoria.CREDITO_PAGADO,
                AccionAuditoria.PAGO_ANULADO,
                AccionAuditoria.CREDITO_REABIERTO,
                AccionAuditoria.CREDITO_EN_MORA);
        verify(notificacionService).crear(eq(TipoNotificacion.CREDITO_EN_MORA), eq(CREDITO_ID), contains("mora"));
    }

    @Test
    void anularUnAbonoQueSaldabaUnCreditoSinCuotasVencidas_loReabreComoAprobado() {
        credito.setFechaInicio(hoy);   // ninguna cuota ha vencido
        List<Pago> abono = service.registrarPago(CREDITO_ID, 1, new BigDecimal("1050.42"));
        assertThat(credito.getEstado()).isEqualTo(Credito.EstadoCredito.PAGADO);

        service.anularAbono(abono.get(0).getId(), "Error de captura");

        assertThat(credito.getEstado()).isEqualTo(Credito.EstadoCredito.APROBADO);
        assertThat(accionesAuditadas()).contains(AccionAuditoria.CREDITO_REABIERTO)
                .doesNotContain(AccionAuditoria.CREDITO_EN_MORA);
        verifyNoInteractions(notificacionService);
    }

    @Test
    void despuesDeAnular_laCuotaVuelveAEstarPendienteYSePuedePagarDeNuevo() {
        sembrar(1, "350.14");
        sembrar(2, "100.00");
        List<Pago> abono = service.registrarPago(CREDITO_ID, 2, new BigDecimal("600.28"));
        service.anularAbono(abono.get(0).getId(), "Monto equivocado");
        assertThat(credito.getEstado()).isEqualTo(Credito.EstadoCredito.EN_MORA);

        List<Pago> nuevo = service.registrarPago(CREDITO_ID, 2, new BigDecimal("600.28"));

        assertThat(nuevo).hasSize(2);
        assertThat(credito.getEstado()).isEqualTo(Credito.EstadoCredito.PAGADO);
        assertThat(sumaPagos()).isEqualByComparingTo("1050.42");
    }

    @Test
    void soloSePuedeAnularElUltimoAbono() {
        credito.setFechaInicio(hoy);
        Long primero = service.registrarPago(CREDITO_ID, 1, new BigDecimal("100.00")).get(0).getId();
        service.registrarPago(CREDITO_ID, 1, new BigDecimal("50.00"));

        assertThatThrownBy(() -> service.anularAbono(primero, "Quiero anular el primero"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("último abono");
    }

    @Test
    void noSePuedeAnularDosVecesElMismoAbono() {
        credito.setFechaInicio(hoy);
        Long id = service.registrarPago(CREDITO_ID, 1, new BigDecimal("100.00")).get(0).getId();
        service.anularAbono(id, "Primera anulación");

        assertThatThrownBy(() -> service.anularAbono(id, "Segunda anulación"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ya fue anulado");
    }

    @Test
    void noSePuedeAnularUnaFilaDeAnulacion() {
        credito.setFechaInicio(hoy);
        Long id = service.registrarPago(CREDITO_ID, 1, new BigDecimal("100.00")).get(0).getId();
        Long idReverso = service.anularAbono(id, "Anulación válida").get(0).getId();

        assertThatThrownBy(() -> service.anularAbono(idReverso, "Anular la anulación"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ya es una anulación");
    }

    @Test
    void elMotivoEsObligatorioYTieneUnMaximoDe300Caracteres() {
        assertThatThrownBy(() -> service.anularAbono(1L, "   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("obligatorio");
        assertThatThrownBy(() -> service.anularAbono(1L, "x".repeat(301)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("300");
    }


    @Test
    void creditoEnMoraQueSePoneAlDia_vuelveAAprobado() {
        credito.setEstado(Credito.EstadoCredito.EN_MORA);   // cuotas 1 y 2 vencidas y sin pagar

        service.registrarPago(CREDITO_ID, 1, new BigDecimal("700.28"));   // cubre ambas

        assertThat(credito.getEstado()).isEqualTo(Credito.EstadoCredito.APROBADO);
        assertThat(accionesAuditadas()).endsWith(AccionAuditoria.CREDITO_AL_DIA);
    }

    @Test
    void creditoEnMoraConUnaCuotaVencidaSinPagar_sigueEnMora() {
        credito.setEstado(Credito.EstadoCredito.EN_MORA);

        service.registrarPago(CREDITO_ID, 1, new BigDecimal("350.14"));   // solo la cuota 1

        assertThat(credito.getEstado()).isEqualTo(Credito.EstadoCredito.EN_MORA);   // la cuota 2 sigue vencida
    }
}
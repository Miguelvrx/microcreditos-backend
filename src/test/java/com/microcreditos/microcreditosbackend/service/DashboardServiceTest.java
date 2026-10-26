package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.dto.CreditoConSaldoMoraDTO;
import com.microcreditos.microcreditosbackend.dto.DashboardResponse;
import com.microcreditos.microcreditosbackend.model.Cliente;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.Pago;
import com.microcreditos.microcreditosbackend.repository.CreditoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Resumen del dashboard. Crédito de referencia: $1000 a 3 meses, cuota 350.14.
 *
 * DashboardService usa la fecha de hoy, así que las fechas de inicio se calculan hacia atrás desde
 * hoy: con inicio = hoy - 2 meses - 10 días, las cuotas 1 y 2 ya vencieron y la 3 todavía no.
 * La mora del interés (MoraCarteraService) se simula; su cálculo ya tiene sus propias pruebas.
 */
class DashboardServiceTest {

    private CreditoRepository creditoRepository;
    private MoraCarteraService moraCarteraService;
    private DashboardService service;

    @BeforeEach
    void preparar() {
        creditoRepository = mock(CreditoRepository.class);
        moraCarteraService = mock(MoraCarteraService.class);   // sin stubs: devuelve un mapa vacío
        service = new DashboardService();
        ReflectionTestUtils.setField(service, "creditoRepository", creditoRepository);
        ReflectionTestUtils.setField(service, "cronogramaService", new CronogramaService());
        ReflectionTestUtils.setField(service, "moraCarteraService", moraCarteraService);
    }

    private Credito credito(long id, Credito.EstadoCredito estado, LocalDate inicio) {
        Cliente cliente = new Cliente();
        cliente.setNombreCompleto("Cliente " + id);

        Credito c = new Credito();
        c.setId(id);
        c.setCliente(cliente);
        c.setMontoSolicitado(new BigDecimal("1000.00"));
        c.setTasaInteresMensual(new BigDecimal("2.5"));
        c.setPlazoMeses(3);
        c.setCuotaMensual(new BigDecimal("350.14"));
        c.setFechaInicio(inicio);
        c.setEstado(estado);
        return c;
    }

    private Pago abono(long id, int cuota, String monto) {
        Pago p = new Pago();
        p.setId(id);
        p.setNumeroCuota(cuota);
        p.setMontoPagado(new BigDecimal(monto));
        p.setFechaPago(LocalDate.now());
        p.setFechaVencimiento(LocalDate.now());
        p.setEstado(Pago.EstadoPago.PAGADO);
        return p;
    }

    private MoraCarteraService.MoraResumen mora(String devengado, String cobrado, String condonado, String saldo) {
        return new MoraCarteraService.MoraResumen(new BigDecimal(devengado), new BigDecimal(cobrado),
                new BigDecimal(condonado), new BigDecimal(saldo), List.of());
    }

    private LocalDate conDosCuotasVencidas() {
        return LocalDate.now().minusMonths(2).minusDays(10);
    }

    // ------------------------------------------------------------ monto en mora

    @Test
    void cuotaVencidaConAbonoParcial_soloCuentaLoQueFalta() {
        // Cuota 1 pagada completa; cuota 2 vencida con 100.00 abonados -> faltan 250.14, no 350.14.
        Credito c = credito(15, Credito.EstadoCredito.EN_MORA, conDosCuotasVencidas());
        c.setPagos(List.of(abono(1, 1, "350.14"), abono(2, 2, "100.00")));
        when(creditoRepository.findAll()).thenReturn(List.of(c));

        DashboardResponse r = service.obtenerResumen();

        assertThat(r.creditosEnMora()).isEqualTo(1);
        assertThat(r.totalEnMora()).isEqualByComparingTo("250.14");
        assertThat(r.creditosEnMoraDetalle()).hasSize(1);
        assertThat(r.creditosEnMoraDetalle().get(0).montoEnMora()).isEqualByComparingTo("250.14");
        assertThat(r.creditosEnMoraDetalle().get(0).cuotasVencidas()).isEqualTo(1);
    }

    @Test
    void sinAbonos_sumaLasCuotasVencidasCompletas() {
        Credito c = credito(15, Credito.EstadoCredito.EN_MORA, conDosCuotasVencidas());
        when(creditoRepository.findAll()).thenReturn(List.of(c));

        DashboardResponse r = service.obtenerResumen();

        assertThat(r.totalEnMora()).isEqualByComparingTo("700.28");   // 2 x 350.14
        assertThat(r.creditosEnMoraDetalle().get(0).cuotasVencidas()).isEqualTo(2);
    }

    @Test
    void creditoAlCorriente_noEstaEnMora() {
        Credito c = credito(15, Credito.EstadoCredito.APROBADO, LocalDate.now().minusDays(5));
        when(creditoRepository.findAll()).thenReturn(List.of(c));

        DashboardResponse r = service.obtenerResumen();

        assertThat(r.creditosVigentes()).isEqualTo(1);
        assertThat(r.creditosEnMora()).isEqualTo(0);
        assertThat(r.totalEnMora()).isEqualByComparingTo("0.00");
        assertThat(r.creditosEnMoraDetalle()).isEmpty();
    }

    @Test
    void creditosPendientesYRechazados_noCuentanComoPrestado() {
        when(creditoRepository.findAll()).thenReturn(List.of(
                credito(1, Credito.EstadoCredito.APROBADO, LocalDate.now()),
                credito(2, Credito.EstadoCredito.PENDIENTE, LocalDate.now()),
                credito(3, Credito.EstadoCredito.RECHAZADO, LocalDate.now())));

        DashboardResponse r = service.obtenerResumen();

        assertThat(r.totalPrestado()).isEqualByComparingTo("1000.00");
        assertThat(r.creditosVigentes()).isEqualTo(1);
    }

    // ------------------------------------------------------------ interés moratorio de la cartera

    @Test
    void interesPorCobrar_incluyeCreditosPagados_deMayorAMenor() {
        when(creditoRepository.findAll()).thenReturn(List.of(
                credito(15, Credito.EstadoCredito.EN_MORA, LocalDate.now()),
                credito(25, Credito.EstadoCredito.PAGADO, LocalDate.now()),
                credito(31, Credito.EstadoCredito.APROBADO, LocalDate.now())));
        when(moraCarteraService.calcular(any(), any())).thenReturn(Map.of(
                15L, mora("13.18", "2.10", "4.00", "7.08"),
                25L, mora("4.90", "0.00", "0.00", "4.90"),
                31L, mora("43.77", "0.00", "0.00", "43.77")));

        DashboardResponse r = service.obtenerResumen();

        assertThat(r.moraDevengada()).isEqualByComparingTo("61.85");
        assertThat(r.moraCobrada()).isEqualByComparingTo("2.10");
        assertThat(r.moraCondonada()).isEqualByComparingTo("4.00");
        assertThat(r.saldoMora()).isEqualByComparingTo("55.75");
        assertThat(r.creditosConSaldoMora()).isEqualTo(3);
        assertThat(r.creditosConSaldoMoraDetalle())
                .extracting(CreditoConSaldoMoraDTO::creditoId)
                .containsExactly(31L, 15L, 25L);
        assertThat(r.creditosConSaldoMoraDetalle().get(2).estado()).isEqualTo("PAGADO");
        // El crédito PAGADO debe interés pero no está vigente.
        assertThat(r.creditosVigentes()).isEqualTo(2);
    }

    @Test
    void creditoSinSaldoDeMora_noApareceEnLaListaPeroSuMovimientoSiSuma() {
        when(creditoRepository.findAll()).thenReturn(List.of(
                credito(15, Credito.EstadoCredito.APROBADO, LocalDate.now())));
        when(moraCarteraService.calcular(any(), any())).thenReturn(Map.of(
                15L, mora("2.10", "2.10", "0.00", "0.00")));

        DashboardResponse r = service.obtenerResumen();

        assertThat(r.moraCobrada()).isEqualByComparingTo("2.10");
        assertThat(r.saldoMora()).isEqualByComparingTo("0.00");
        assertThat(r.creditosConSaldoMora()).isEqualTo(0);
        assertThat(r.creditosConSaldoMoraDetalle()).isEmpty();
    }

    // ------------------------------------------------------------ aging y PAR (punto 40)
    //
    // Con inicio = hoy - 2 meses - 10 días, la cuota 1 lleva unos 40 días vencida (tramo 31-60) y la
    // cuota 2 unos 10 (tramo 1-30). Los días exactos varían un poco según el largo de los meses, pero
    // siempre quedan lejos de los límites de 30 y 60.

    @Test
    void aging_repartePorCuotaYSuSumaEsIgualAlEnMora() {
        Credito c = credito(15, Credito.EstadoCredito.EN_MORA, conDosCuotasVencidas());
        when(creditoRepository.findAll()).thenReturn(List.of(c));

        DashboardResponse r = service.obtenerResumen();

        assertThat(r.aging()).hasSize(4);
        assertThat(r.aging().get(0).monto()).isEqualByComparingTo("350.14");   // 1-30: cuota 2
        assertThat(r.aging().get(1).monto()).isEqualByComparingTo("350.14");   // 31-60: cuota 1
        assertThat(r.aging().get(2).monto()).isEqualByComparingTo("0.00");
        assertThat(r.aging().get(3).monto()).isEqualByComparingTo("0.00");
        // El mismo crédito aparece en los dos tramos en los que tiene cuotas vencidas.
        assertThat(r.aging().get(0).creditos()).isEqualTo(1);
        assertThat(r.aging().get(1).creditos()).isEqualTo(1);

        BigDecimal sumaAging = r.aging().stream()
                .map(t -> t.monto())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumaAging).isEqualByComparingTo(r.totalEnMora());
    }

    @Test
    void aging_conAbonoParcialUsaLoQueFalta() {
        // Cuota 1 pagada; cuota 2 (unos 10 días vencida) con 100.00 abonados -> faltan 250.14 en el tramo 1-30.
        Credito c = credito(15, Credito.EstadoCredito.EN_MORA, conDosCuotasVencidas());
        c.setPagos(List.of(abono(1, 1, "350.14"), abono(2, 2, "100.00")));
        when(creditoRepository.findAll()).thenReturn(List.of(c));

        DashboardResponse r = service.obtenerResumen();

        assertThat(r.aging().get(0).monto()).isEqualByComparingTo("250.14");
        assertThat(r.aging().get(1).monto()).isEqualByComparingTo("0.00");
        assertThat(r.aging().get(0).monto()).isEqualByComparingTo(r.totalEnMora());
    }

    @Test
    void par_usaElCapitalPendienteDelCreditoCompletoSobreLaCarteraVigente() {
        // Crédito 1: cuota 1 con unos 40 días de atraso y sin abonos -> los $1000 de capital están en riesgo.
        // Crédito 2: al corriente, también con $1000 de capital pendiente.
        when(creditoRepository.findAll()).thenReturn(List.of(
                credito(15, Credito.EstadoCredito.EN_MORA, conDosCuotasVencidas()),
                credito(16, Credito.EstadoCredito.APROBADO, LocalDate.now().minusDays(5))));

        DashboardResponse r = service.obtenerResumen();

        assertThat(r.saldoCapitalVigente()).isEqualByComparingTo("2000.00");
        assertThat(r.par()).hasSize(3);
        assertThat(r.par().get(0).dias()).isEqualTo(30);
        assertThat(r.par().get(0).capitalEnRiesgo()).isEqualByComparingTo("1000.00");   // no 350.14
        assertThat(r.par().get(0).porcentaje()).isEqualByComparingTo("50.0");
        assertThat(r.par().get(0).creditos()).isEqualTo(1);
        // Su atraso máximo ronda los 40 días: no llega al PAR 60 ni al PAR 90.
        assertThat(r.par().get(1).porcentaje()).isEqualByComparingTo("0.0");
        assertThat(r.par().get(2).porcentaje()).isEqualByComparingTo("0.0");
    }

    @Test
    void par_unaCuotaCompletaBajaElCapitalPendiente() {
        // Cuota 1 pagada: el capital pendiente baja a 674.86. La cuota 2 lleva unos 10 días: no entra al PAR 30.
        Credito c = credito(15, Credito.EstadoCredito.EN_MORA, conDosCuotasVencidas());
        c.setPagos(List.of(abono(1, 1, "350.14")));
        when(creditoRepository.findAll()).thenReturn(List.of(c));

        DashboardResponse r = service.obtenerResumen();

        assertThat(r.saldoCapitalVigente()).isEqualByComparingTo("674.86");
        assertThat(r.par().get(0).capitalEnRiesgo()).isEqualByComparingTo("0.00");
        assertThat(r.par().get(0).porcentaje()).isEqualByComparingTo("0.0");
        assertThat(r.aging().get(0).monto()).isEqualByComparingTo("350.14");
    }

    @Test
    void indicadoresDeRiesgo_losCreditosPagadosNoEntranAlDenominador() {
        when(creditoRepository.findAll()).thenReturn(List.of(
                credito(15, Credito.EstadoCredito.APROBADO, LocalDate.now().minusDays(5)),
                credito(25, Credito.EstadoCredito.PAGADO, conDosCuotasVencidas()),
                credito(31, Credito.EstadoCredito.PENDIENTE, LocalDate.now())));

        DashboardResponse r = service.obtenerResumen();

        assertThat(r.saldoCapitalVigente()).isEqualByComparingTo("1000.00");
        // El PAGADO tiene fechas viejas, pero no se evalúa: no hay atraso en el aging.
        assertThat(r.aging()).allSatisfy(t -> assertThat(t.monto()).isEqualByComparingTo("0.00"));
    }

    @Test
    void indicadoresDeRiesgo_sinCreditosVigentesTodoEsCero() {
        when(creditoRepository.findAll()).thenReturn(List.of());

        DashboardResponse r = service.obtenerResumen();

        assertThat(r.saldoCapitalVigente()).isEqualByComparingTo("0.00");
        assertThat(r.aging()).hasSize(4);
        assertThat(r.par()).hasSize(3);
        assertThat(r.par().get(0).porcentaje()).isEqualByComparingTo("0.0");
        assertThat(r.par().get(2).porcentaje()).isEqualByComparingTo("0.0");
    }
}
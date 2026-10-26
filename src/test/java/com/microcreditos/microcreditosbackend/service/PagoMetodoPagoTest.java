package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.Cliente;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.MetodoPago;
import com.microcreditos.microcreditosbackend.model.Pago;
import com.microcreditos.microcreditosbackend.repository.CreditoRepository;
import com.microcreditos.microcreditosbackend.repository.PagoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Método de pago y referencia (V4) en PagoService. Mismo esquema que PagoServiceTest: los repositorios
 * son un "almacén" en memoria y CronogramaService es el real. El crédito empieza hoy, así que ninguna
 * cuota está vencida y el estado del crédito no interviene en estas pruebas.
 */
@ExtendWith(MockitoExtension.class)
class PagoMetodoPagoTest {

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

        // $1000 a 3 meses, 2.5% mensual, cuota 350.14 (total 1050.42), iniciado hoy.
        credito = new Credito();
        credito.setId(CREDITO_ID);
        credito.setMontoSolicitado(new BigDecimal("1000.00"));
        credito.setTasaInteresMensual(new BigDecimal("2.5"));
        credito.setPlazoMeses(3);
        credito.setCuotaMensual(new BigDecimal("350.14"));
        credito.setFechaInicio(hoy);
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
        lenient().when(pagoRepository.findByReferenciaPago(anyString())).thenAnswer(inv -> {
            String referencia = inv.getArgument(0);
            return almacen.stream().filter(p -> referencia.equals(p.getReferenciaPago())).toList();
        });
    }

    @Test
    void sinMetodo_seRegistraComoEfectivoSinReferencia() {
        List<Pago> creados = service.registrarPago(CREDITO_ID, 1, new BigDecimal("100.00"));

        assertThat(creados).hasSize(1);
        assertThat(creados.get(0).getMetodoPago()).isEqualTo(MetodoPago.EFECTIVO);
        assertThat(creados.get(0).getReferenciaPago()).isNull();
    }

    @Test
    void transferenciaODepositoSinReferencia_seRechazanYNoGuardanNada() {
        assertThatThrownBy(() -> service.registrarPago(CREDITO_ID, 1, new BigDecimal("100.00"),
                MetodoPago.TRANSFERENCIA, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("referencia")
                .hasMessageContaining("transferencia");

        assertThatThrownBy(() -> service.registrarPago(CREDITO_ID, 1, new BigDecimal("100.00"),
                MetodoPago.DEPOSITO, "   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("depósito");

        assertThat(almacen).isEmpty();
    }

    @Test
    void depositoConReferencia_laGuardaSinEspaciosYEnMayusculas() {
        List<Pago> creados = service.registrarPago(CREDITO_ID, 1, new BigDecimal("100.00"),
                MetodoPago.DEPOSITO, "  fol-123x ");

        assertThat(creados.get(0).getMetodoPago()).isEqualTo(MetodoPago.DEPOSITO);
        assertThat(creados.get(0).getReferenciaPago()).isEqualTo("FOL-123X");
    }

    @Test
    void referenciaDeMasDe50Caracteres_seRechazaSinRecortarla() {
        String larga = "A".repeat(PagoService.MAX_REFERENCIA + 1);

        assertThatThrownBy(() -> service.registrarPago(CREDITO_ID, 1, new BigDecimal("100.00"),
                MetodoPago.TRANSFERENCIA, larga))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("50");

        assertThat(almacen).isEmpty();
    }

    @Test
    void efectivoConReferencia_laConservaAunqueNoSeaObligatoria() {
        List<Pago> creados = service.registrarPago(CREDITO_ID, 1, new BigDecimal("100.00"),
                MetodoPago.EFECTIVO, "rec-1");

        assertThat(creados.get(0).getMetodoPago()).isEqualTo(MetodoPago.EFECTIVO);
        assertThat(creados.get(0).getReferenciaPago()).isEqualTo("REC-1");
    }

    @Test
    void derrame_copiaMetodoYReferenciaATodasLasFilasDelAbono() {
        List<Pago> creados = service.registrarPago(CREDITO_ID, 1, new BigDecimal("500.00"),
                MetodoPago.TRANSFERENCIA, "clave9");

        assertThat(creados).hasSize(2);   // cuota 1 completa (350.14) y 149.86 a la cuota 2
        assertThat(creados).allMatch(p -> p.getMetodoPago() == MetodoPago.TRANSFERENCIA);
        assertThat(creados).allMatch(p -> "CLAVE9".equals(p.getReferenciaPago()));
    }

    @Test
    void anulacion_heredaMetodoYReferenciaDelPagoOriginal() {
        Pago original = service.registrarPago(CREDITO_ID, 1, new BigDecimal("100.00"),
                MetodoPago.TRANSFERENCIA, "abc1").get(0);

        List<Pago> reversos = service.anularAbono(original.getId(), "Error de captura");

        assertThat(reversos).hasSize(1);
        assertThat(reversos.get(0).getEstado()).isEqualTo(Pago.EstadoPago.ANULACION);
        assertThat(reversos.get(0).getMetodoPago()).isEqualTo(MetodoPago.TRANSFERENCIA);
        assertThat(reversos.get(0).getReferenciaPago()).isEqualTo("ABC1");
    }

    @Test
    void creditosConReferencia_avisaDelAbonoActivoPeroIgnoraLosAnulados() {
        Pago original = service.registrarPago(CREDITO_ID, 1, new BigDecimal("100.00"),
                MetodoPago.TRANSFERENCIA, "ref9").get(0);

        // Sin importar mayúsculas ni espacios, la referencia se reconoce
        assertThat(service.creditosConReferencia(" Ref9 ")).containsExactly(CREDITO_ID);
        assertThat(service.creditosConReferencia("OTRA")).isEmpty();
        assertThat(service.creditosConReferencia("  ")).isEmpty();

        service.anularAbono(original.getId(), "Se capturó dos veces");

        // El abono y su reverso comparten referencia, pero el abono ya no está activo
        assertThat(service.creditosConReferencia("REF9")).isEmpty();
    }
}
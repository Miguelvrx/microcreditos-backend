package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.Cliente;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.MovimientoMora;
import com.microcreditos.microcreditosbackend.model.TipoMovimientoMora;
import com.microcreditos.microcreditosbackend.repository.CreditoRepository;
import com.microcreditos.microcreditosbackend.repository.MovimientoMoraRepository;
import com.microcreditos.microcreditosbackend.repository.PagoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * Recibo de cobro de mora. Crédito de referencia: $1000 a 3 meses, cuota 350.14, inicio 01/08/2026,
 * con 5% mensual y 3 días de gracia. El 11/09/2026 el interés devengado es 4.08 (cuota 1, 7 días).
 *
 * Movimientos: 1 cobro de 1.00, 2 condonación de 0.50, 3 cobro de 2.00 (el del recibo) y 4 cobro
 * posterior de 0.30.
 */
@ExtendWith(MockitoExtension.class)
class ReciboMoraServiceTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 9, 11);

    @Mock private MovimientoMoraRepository movimientoMoraRepository;
    @Mock private CreditoRepository creditoRepository;
    @Mock private PagoRepository pagoRepository;

    private ReciboMoraService service;
    private final List<MovimientoMora> movimientos = new ArrayList<>();

    @BeforeEach
    void preparar() {
        Cliente cliente = new Cliente();
        cliente.setNombreCompleto("NEZ CRIS");
        cliente.setNumeroDocumento("DOC-123");

        Credito credito = new Credito();
        credito.setId(15L);
        credito.setCliente(cliente);
        credito.setMontoSolicitado(new BigDecimal("1000.00"));
        credito.setTasaInteresMensual(new BigDecimal("2.5"));
        credito.setPlazoMeses(3);
        credito.setCuotaMensual(new BigDecimal("350.14"));
        credito.setFechaInicio(LocalDate.of(2026, 8, 1));

        service = new ReciboMoraService(movimientoMoraRepository, creditoRepository, pagoRepository,
                new InteresMoratorioService(new BigDecimal("5"), 3));

        lenient().when(creditoRepository.findById(15L)).thenReturn(Optional.of(credito));
        lenient().when(pagoRepository.findByCreditoId(15L)).thenReturn(List.of());
        lenient().when(movimientoMoraRepository.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return movimientos.stream().filter(m -> id.equals(m.getId())).findFirst();
        });
        lenient().when(movimientoMoraRepository.findByCreditoIdOrderByIdDesc(15L)).thenAnswer(inv -> {
            List<MovimientoMora> copia = new ArrayList<>(movimientos);
            Collections.reverse(copia);
            return copia;
        });

        movimiento(1, TipoMovimientoMora.COBRO, "1.00", "admin");
        movimiento(2, TipoMovimientoMora.CONDONACION, "0.50", "admin");
        movimiento(3, TipoMovimientoMora.COBRO, "2.00", "Miguel");
        movimiento(4, TipoMovimientoMora.COBRO, "0.30", "admin");
    }

    private void movimiento(long id, TipoMovimientoMora tipo, String monto, String usuario) {
        MovimientoMora m = new MovimientoMora();
        m.setId(id);
        m.setCreditoId(15L);
        m.setTipo(tipo);
        m.setMonto(new BigDecimal(monto));
        m.setFecha(FECHA);
        m.setUsuario(usuario);
        if (tipo == TipoMovimientoMora.CONDONACION) {
            m.setMotivo("Ajuste");
        }
        movimientos.add(m);
    }

    // ------------------------------------------------------------ datos

    @Test
    void elReciboDeUnCobro_calculaElSaldoALaFechaDeEseCobro() {
        ReciboMoraService.DatosReciboMora datos = service.calcularDatos(3L);

        assertThat(datos.numero()).isEqualTo("M-000003");
        assertThat(datos.fecha()).isEqualTo(FECHA);
        assertThat(datos.cliente()).isEqualTo("NEZ CRIS");
        assertThat(datos.documento()).isEqualTo("DOC-123");
        assertThat(datos.creditoId()).isEqualTo(15L);
        assertThat(datos.monto()).isEqualByComparingTo("2.00");
        assertThat(datos.recibio()).isEqualTo("Miguel");
        // 4.08 devengado - (1.00 + 0.50 + 2.00) aplicado hasta este cobro = 0.58. El cobro 4 (0.30) es posterior.
        assertThat(datos.saldoMora()).isEqualByComparingTo("0.58");
    }

    @Test
    void elReciboDeUnCobroAnterior_noCambiaPorLosCobrosPosteriores() {
        ReciboMoraService.DatosReciboMora datos = service.calcularDatos(1L);

        assertThat(datos.numero()).isEqualTo("M-000001");
        assertThat(datos.saldoMora()).isEqualByComparingTo("3.08");   // 4.08 - 1.00
    }

    @Test
    void unaCondonacionNoGeneraRecibo() {
        assertThatThrownBy(() -> service.calcularDatos(2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("condonación");
    }

    @Test
    void unMovimientoInexistenteSeReportaComoNoEncontrado() {
        assertThatThrownBy(() -> service.calcularDatos(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    // ------------------------------------------------------------ PDF

    @Test
    void elPdfEsUnaSolaPaginaDe80mmConLosDatosDelRecibo() throws Exception {
        ReciboMoraService.ReciboMoraGenerado recibo = service.generarRecibo(3L);

        assertThat(recibo.numero()).isEqualTo("M-000003");
        assertThat(new String(recibo.pdf(), 0, 4)).isEqualTo("%PDF");

        PdfReader reader = new PdfReader(recibo.pdf());
        try {
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
            assertThat(reader.getPageSize(1).getWidth()).isCloseTo(226.77f, within(0.5f));
            assertThat(reader.getPageSize(1).getHeight()).isBetween(180f, 500f);

            String texto = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(texto).contains("M-000003", "NEZ CRIS", "2.00", "Miguel");
        } finally {
            reader.close();
        }
    }
}
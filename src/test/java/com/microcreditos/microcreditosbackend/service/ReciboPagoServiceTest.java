package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.Cliente;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.Pago;
import com.microcreditos.microcreditosbackend.repository.PagoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * Pruebas del recibo de pago. Los datos se arman con el caso real del crédito #15
 * ($1000 a 3 meses, cuota 350.14, total 1050.42): cuota 1 completa, cuota 2 con 100.00,
 * y un abono de 600.28 que se derrama en las cuotas 2 y 3.
 */
@ExtendWith(MockitoExtension.class)
class ReciboPagoServiceTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 10, 6);

    @Mock private PagoRepository pagoRepository;
    @Mock private JdbcTemplate jdbcTemplate;
    @InjectMocks private ReciboPagoService service;

    private final List<Pago> pagos = new ArrayList<>();
    private Credito credito;

    @BeforeEach
    void preparar() {
        Cliente cliente = new Cliente();
        cliente.setNombreCompleto("NEZ CRIS");
        cliente.setNumeroDocumento("DOC-123");

        credito = new Credito();
        credito.setId(15L);
        credito.setCliente(cliente);
        credito.setMontoSolicitado(new BigDecimal("1000.00"));
        credito.setTasaInteresMensual(new BigDecimal("2.5"));
        credito.setPlazoMeses(3);
        credito.setCuotaMensual(new BigDecimal("350.14"));
        credito.setFechaInicio(LocalDate.of(2026, 8, 1));

        lenient().when(pagoRepository.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return pagos.stream().filter(p -> id.equals(p.getId())).findFirst();
        });
        lenient().when(pagoRepository.findByCreditoId(15L)).thenAnswer(inv -> new ArrayList<>(pagos));
        lenient().when(jdbcTemplate.queryForList(anyString(), eq(String.class), any())).thenReturn(List.of("admin"));
    }

    private Pago pago(long id, int cuota, String monto, String abonoRef, Pago.EstadoPago estado, Long originalId) {
        Pago p = new Pago();
        p.setId(id);
        p.setCredito(credito);
        p.setNumeroCuota(cuota);
        p.setMontoPagado(new BigDecimal(monto));
        p.setFechaPago(FECHA);
        p.setFechaVencimiento(credito.getFechaInicio().plusMonths(cuota));
        p.setEstado(estado);
        p.setAbonoRef(abonoRef);
        p.setPagoOriginalId(originalId);
        return p;
    }

    /** Estado del #15 justo después del abono de 600.28: pagos 1 y 2 anteriores a V2 (sin abonoRef), 3 y 4 del derrame. */
    private void sembrarCreditoSaldadoConDerrame() {
        pagos.add(pago(1, 1, "350.14", null, Pago.EstadoPago.PAGADO, null));
        pagos.add(pago(2, 2, "100.00", null, Pago.EstadoPago.PAGADO, null));
        pagos.add(pago(3, 2, "250.14", "abono-A", Pago.EstadoPago.ATRASADO, null));
        pagos.add(pago(4, 3, "350.14", "abono-A", Pago.EstadoPago.PAGADO, null));
    }

    // ================================================================ datos

    @Test
    void abonoConDerrame_saleEnUnSoloReciboConAmbasCuotas() {
        sembrarCreditoSaldadoConDerrame();

        ReciboPagoService.DatosRecibo datos = service.calcularDatos(4L);   // sirve cualquier fila del abono

        assertThat(datos.numero()).isEqualTo("R-000003");                  // id de la primera fila del abono
        assertThat(datos.fecha()).isEqualTo(FECHA);
        assertThat(datos.cliente()).isEqualTo("NEZ CRIS");
        assertThat(datos.documento()).isEqualTo("DOC-123");
        assertThat(datos.creditoId()).isEqualTo(15L);
        assertThat(datos.total()).isEqualByComparingTo("600.28");
        assertThat(datos.saldoCredito()).isEqualByComparingTo("0.00");
        assertThat(datos.recibio()).isEqualTo("admin");

        assertThat(datos.lineas()).hasSize(2);
        assertThat(datos.lineas().get(0).numeroCuota()).isEqualTo(2);
        assertThat(datos.lineas().get(0).aplicado()).isEqualByComparingTo("250.14");
        assertThat(datos.lineas().get(0).completa()).isTrue();
        assertThat(datos.lineas().get(1).numeroCuota()).isEqualTo(3);
        assertThat(datos.lineas().get(1).aplicado()).isEqualByComparingTo("350.14");
        assertThat(datos.lineas().get(1).completa()).isTrue();
    }

    @Test
    void reciboDeUnAbonoAnterior_calculaElSaldoALaFechaDeEseAbono() {
        sembrarCreditoSaldadoConDerrame();

        // El pago 2 (100.00 a la cuota 2) es anterior al derrame: entonces el crédito
        // tenía 350.14 + 100.00 pagados y la cuota 2 seguía incompleta.
        ReciboPagoService.DatosRecibo datos = service.calcularDatos(2L);

        assertThat(datos.numero()).isEqualTo("R-000002");
        assertThat(datos.total()).isEqualByComparingTo("100.00");
        assertThat(datos.saldoCredito()).isEqualByComparingTo("600.28");   // 1050.42 - 450.14
        assertThat(datos.lineas()).hasSize(1);
        assertThat(datos.lineas().get(0).completa()).isFalse();
        assertThat(datos.lineas().get(0).resta()).isEqualByComparingTo("250.14");
    }

    @Test
    void unAbonoAnuladoNoGeneraRecibo() {
        pagos.add(pago(1, 1, "100.00", "abono-A", Pago.EstadoPago.PAGADO, null));
        pagos.add(pago(2, 1, "-100.00", "abono-A", Pago.EstadoPago.ANULACION, 1L));

        assertThatThrownBy(() -> service.calcularDatos(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("anulado");
    }

    @Test
    void unaFilaDeAnulacionNoGeneraRecibo() {
        pagos.add(pago(1, 1, "100.00", "abono-A", Pago.EstadoPago.PAGADO, null));
        pagos.add(pago(2, 1, "-100.00", "abono-A", Pago.EstadoPago.ANULACION, 1L));

        assertThatThrownBy(() -> service.calcularDatos(2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("anulación");
    }

    @Test
    void unPagoInexistenteSeReportaComoNoEncontrado() {
        assertThatThrownBy(() -> service.calcularDatos(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    void sinRegistroEnLaAuditoria_elUsuarioSaleComoGuion() {
        sembrarCreditoSaldadoConDerrame();
        lenient().when(jdbcTemplate.queryForList(anyString(), eq(String.class), any())).thenReturn(List.of());

        assertThat(service.calcularDatos(3L).recibio()).isEqualTo("—");
    }

    // ================================================================ PDF

    @Test
    void elPdfEsUnaSolaPaginaDe80mmConLosDatosDelRecibo() throws Exception {
        sembrarCreditoSaldadoConDerrame();

        ReciboPagoService.ReciboGenerado recibo = service.generarRecibo(3L);

        assertThat(recibo.numero()).isEqualTo("R-000003");
        assertThat(new String(recibo.pdf(), 0, 4)).isEqualTo("%PDF");

        PdfReader reader = new PdfReader(recibo.pdf());
        try {
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
            assertThat(reader.getPageSize(1).getWidth()).isCloseTo(226.77f, within(0.5f));
            // Alto ajustado al contenido: ni más grande que la página de medición ni un recorte.
            assertThat(reader.getPageSize(1).getHeight()).isBetween(220f, 600f);

            String texto = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(texto).contains("R-000003", "NEZ CRIS", "600.28", "admin");
        } finally {
            reader.close();
        }
    }
}
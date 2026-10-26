package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.dto.DashboardResponse;
import com.microcreditos.microcreditosbackend.dto.ParDTO;
import com.microcreditos.microcreditosbackend.dto.ReporteCarteraFiltros;
import com.microcreditos.microcreditosbackend.dto.TramoAgingDTO;
import com.microcreditos.microcreditosbackend.model.Cliente;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.Pago;
import com.microcreditos.microcreditosbackend.repository.CreditoRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Resumen del reporte de cartera con filtros (el que genera el botón del dashboard). Se genera el
 * Excel y se lee de vuelta: así se comprueba lo que de verdad ve el usuario.
 *
 * Debe coincidir con el dashboard: "vigente" = APROBADO o EN_MORA, y "En mora" = lo que falta de las
 * cuotas ya vencidas (no el saldo total del crédito). El interés moratorio se simula.
 *
 * Desde el punto 40 el reporte también trae la sección "Calidad de cartera" (PAR 30/60/90 y aging).
 * Con filtros se calcula sobre los créditos listados; sin filtros se toma del dashboard.
 */
class ReporteServiceTest {

    private static final ReporteCarteraFiltros FILTRO_CLIENTE = new ReporteCarteraFiltros(
            List.of(Credito.EstadoCredito.APROBADO, Credito.EstadoCredito.EN_MORA), 99L, null, null);

    private static final ReporteCarteraFiltros SIN_FILTROS = new ReporteCarteraFiltros(null, null, null, null);

    private CreditoRepository creditoRepository;
    private DashboardService dashboardService;
    private MoraCarteraService moraCarteraService;
    private ReporteService service;

    @BeforeEach
    void preparar() {
        creditoRepository = mock(CreditoRepository.class);
        dashboardService = mock(DashboardService.class);
        moraCarteraService = mock(MoraCarteraService.class);
        service = new ReporteService();
        ReflectionTestUtils.setField(service, "creditoRepository", creditoRepository);
        ReflectionTestUtils.setField(service, "dashboardService", dashboardService);
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

    private MoraCarteraService.MoraResumen mora(String saldo) {
        return new MoraCarteraService.MoraResumen(new BigDecimal(saldo), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal(saldo), List.of());
    }

    /** Crédito con las cuotas 1 y 2 vencidas; la 1 pagada y la 2 con 100.00 abonados: faltan 250.14. */
    private Credito creditoEnMoraConAbonoParcial() {
        Credito c = credito(15, Credito.EstadoCredito.EN_MORA, LocalDate.now().minusMonths(2).minusDays(10));
        c.setPagos(List.of(abono(1, 1, "350.14"), abono(2, 2, "100.00")));
        return c;
    }

    /** Crédito sin abonos con las cuotas 1 (unos 40 días de atraso) y 2 (unos 10) vencidas. */
    private Credito creditoEnMoraSinAbonos() {
        return credito(15, Credito.EstadoCredito.EN_MORA, LocalDate.now().minusMonths(2).minusDays(10));
    }

    private Map<String, Double> resumenDelExcel(List<Credito> creditos) throws IOException {
        when(creditoRepository.buscarParaReporte(any(), any(), any(), any())).thenReturn(creditos);

        // Con un cliente en el filtro no es el reporte "sin filtros": toma el resumen de los créditos listados.
        byte[] excel = service.generarReporteCarteraExcel(
                new ReporteCarteraFiltros(List.of(Credito.EstadoCredito.APROBADO, Credito.EstadoCredito.EN_MORA),
                        99L, null, null));

        Map<String, Double> resumen = new HashMap<>();
        try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(excel))) {
            Sheet hoja = libro.getSheetAt(0);
            for (int i = 0; i <= 10; i++) {   // el resumen está en las primeras filas
                Row fila = hoja.getRow(i);
                if (fila == null || fila.getCell(0) == null || fila.getCell(1) == null) continue;
                if (fila.getCell(0).getCellType() == CellType.STRING
                        && fila.getCell(1).getCellType() == CellType.NUMERIC) {
                    resumen.put(fila.getCell(0).getStringCellValue(), fila.getCell(1).getNumericCellValue());
                }
            }
        }
        return resumen;
    }

    @Test
    void enMora_cuentaLoQueFaltaDeLasCuotasVencidas_noElSaldoTotalDelCredito() {
        // El saldo total del crédito sería 600.28; lo realmente vencido son 250.14.
        Credito alCorriente = credito(16, Credito.EstadoCredito.APROBADO, LocalDate.now().minusDays(5));
        when(moraCarteraService.calcular(any(), any())).thenReturn(Map.of(15L, mora("7.08")));

        Map<String, Double> r = assertDoesNotThrowYLeer(List.of(creditoEnMoraConAbonoParcial(), alCorriente));

        assertThat(r.get("En mora")).isCloseTo(250.14, within(0.001));
    }

    @Test
    void creditosVigentes_incluyenLosEnMora_comoEnElDashboard() {
        Credito alCorriente = credito(16, Credito.EstadoCredito.APROBADO, LocalDate.now().minusDays(5));
        when(moraCarteraService.calcular(any(), any())).thenReturn(Map.of(15L, mora("7.08")));

        Map<String, Double> r = assertDoesNotThrowYLeer(List.of(creditoEnMoraConAbonoParcial(), alCorriente));

        assertThat(r.get("Créditos vigentes")).isEqualTo(2.0);   // el EN_MORA y el APROBADO
    }

    @Test
    void interesPorCobrar_sumaElSaldoDeMoraDeLosCreditosListados() {
        Credito alCorriente = credito(16, Credito.EstadoCredito.APROBADO, LocalDate.now().minusDays(5));
        when(moraCarteraService.calcular(any(), any())).thenReturn(Map.of(
                15L, mora("7.08"),
                16L, mora("5.08")));

        Map<String, Double> r = assertDoesNotThrowYLeer(List.of(creditoEnMoraConAbonoParcial(), alCorriente));

        assertThat(r.get("Interés moratorio por cobrar")).isCloseTo(12.16, within(0.001));
    }

    @Test
    void creditoPagado_noEsVigente_peroSuInteresSiSuma() {
        Credito pagado = credito(25, Credito.EstadoCredito.PAGADO, LocalDate.now().minusMonths(4));
        when(moraCarteraService.calcular(any(), any())).thenReturn(Map.of(25L, mora("4.90")));

        Map<String, Double> r = assertDoesNotThrowYLeer(List.of(pagado));

        assertThat(r.get("Créditos vigentes")).isEqualTo(0.0);
        assertThat(r.get("En mora")).isCloseTo(0.0, within(0.001));
        assertThat(r.get("Interés moratorio por cobrar")).isCloseTo(4.90, within(0.001));
    }

    /** Envuelve la lectura del Excel para poder usarla sin declarar throws en cada prueba. */
    private Map<String, Double> assertDoesNotThrowYLeer(List<Credito> creditos) {
        try {
            return resumenDelExcel(creditos);
        } catch (IOException e) {
            throw new AssertionError("No se pudo leer el Excel generado", e);
        }
    }

    // ------------------------------------------------------------ calidad de cartera (punto 40)

    /**
     * Lee TODA la hoja: por cada fila cuya primera celda es texto guarda hasta tres valores numéricos
     * de las columnas siguientes (NaN si la celda no es numérica). Así se busca por etiqueta, por
     * ejemplo "PAR 30" -> [porcentaje como fracción, capital en riesgo, créditos].
     */
    private Map<String, double[]> filasDelExcel(byte[] excel) throws IOException {
        Map<String, double[]> filas = new HashMap<>();
        try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(excel))) {
            Sheet hoja = libro.getSheetAt(0);
            for (int i = 0; i <= hoja.getLastRowNum(); i++) {
                Row fila = hoja.getRow(i);
                if (fila == null || fila.getCell(0) == null || fila.getCell(0).getCellType() != CellType.STRING) continue;

                double[] valores = new double[3];
                for (int c = 0; c < 3; c++) {
                    Cell celda = fila.getCell(c + 1);
                    valores[c] = (celda != null && celda.getCellType() == CellType.NUMERIC)
                            ? celda.getNumericCellValue()
                            : Double.NaN;
                }
                filas.put(fila.getCell(0).getStringCellValue(), valores);
            }
        }
        return filas;
    }

    private Map<String, double[]> excelCartera(List<Credito> creditos, ReporteCarteraFiltros filtros) {
        when(creditoRepository.buscarParaReporte(any(), any(), any(), any())).thenReturn(creditos);
        try {
            return filasDelExcel(service.generarReporteCarteraExcel(filtros));
        } catch (IOException e) {
            throw new AssertionError("No se pudo leer el Excel generado", e);
        }
    }

    private DashboardResponse respuestaDelDashboard() {
        List<TramoAgingDTO> aging = List.of(
                new TramoAgingDTO("1–30 días", new BigDecimal("700.28"), 2),
                new TramoAgingDTO("31–60 días", new BigDecimal("350.14"), 1),
                new TramoAgingDTO("61–90 días", new BigDecimal("0.00"), 0),
                new TramoAgingDTO("Más de 90 días", new BigDecimal("0.00"), 0));
        List<ParDTO> par = List.of(
                new ParDTO(30, new BigDecimal("1000.00"), new BigDecimal("7.7"), 1),
                new ParDTO(60, new BigDecimal("0.00"), new BigDecimal("0.0"), 0),
                new ParDTO(90, new BigDecimal("0.00"), new BigDecimal("0.0"), 0));

        return new DashboardResponse(new BigDecimal("5000.00"), new BigDecimal("1000.00"), new BigDecimal("1050.42"),
                3, 2, List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, List.of(),
                new BigDecimal("12982.98"), aging, par);
    }

    @Test
    void calidadDeCartera_conFiltrosSeCalculaSobreLosCreditosListados() {
        // Crédito 15: cuota 1 con ~40 días de atraso y cuota 2 con ~10, sin abonos -> $1000 de capital en riesgo.
        // Crédito 16: al corriente, con otros $1000 de capital pendiente.
        Credito alCorriente = credito(16, Credito.EstadoCredito.APROBADO, LocalDate.now().minusDays(5));

        Map<String, double[]> f = excelCartera(List.of(creditoEnMoraSinAbonos(), alCorriente), FILTRO_CLIENTE);

        assertThat(f.get("Capital pendiente de la cartera vigente")[0]).isCloseTo(2000.0, within(0.001));

        // PAR 30 = 1000 / 2000 = 50 % (se guarda como fracción 0.5); PAR 60 y PAR 90 en cero
        assertThat(f.get("PAR 30")[0]).isCloseTo(0.5, within(0.0001));
        assertThat(f.get("PAR 30")[1]).isCloseTo(1000.0, within(0.001));
        assertThat(f.get("PAR 30")[2]).isEqualTo(1.0);
        assertThat(f.get("PAR 60")[0]).isCloseTo(0.0, within(0.0001));
        assertThat(f.get("PAR 90")[0]).isCloseTo(0.0, within(0.0001));

        // Aging por cuota: la cuota 2 en 1–30 y la cuota 1 en 31–60
        assertThat(f.get("1–30 días")[0]).isCloseTo(350.14, within(0.001));
        assertThat(f.get("1–30 días")[1]).isEqualTo(1.0);
        assertThat(f.get("31–60 días")[0]).isCloseTo(350.14, within(0.001));
        assertThat(f.get("31–60 días")[1]).isEqualTo(1.0);
        assertThat(f.get("61–90 días")[0]).isCloseTo(0.0, within(0.001));
        assertThat(f.get("Más de 90 días")[0]).isCloseTo(0.0, within(0.001));
    }

    @Test
    void calidadDeCartera_elAgingCuadraConElEnMoraDelResumen() {
        // Cuota 1 pagada; la 2 (~10 días) con 100.00 abonados -> faltan 250.14 en el tramo 1–30.
        Map<String, double[]> f = excelCartera(List.of(creditoEnMoraConAbonoParcial()), FILTRO_CLIENTE);

        assertThat(f.get("En mora")[0]).isCloseTo(250.14, within(0.001));
        assertThat(f.get("1–30 días")[0]).isCloseTo(250.14, within(0.001));
        assertThat(f.get("31–60 días")[0]).isCloseTo(0.0, within(0.001));

        // Con la cuota 1 completa el capital pendiente baja a 674.86, y 10 días de atraso no entran al PAR 30.
        assertThat(f.get("Capital pendiente de la cartera vigente")[0]).isCloseTo(674.86, within(0.001));
        assertThat(f.get("PAR 30")[0]).isCloseTo(0.0, within(0.0001));
    }

    @Test
    void calidadDeCartera_sinCarteraVigenteNoSeImprime() {
        Credito pagado = credito(25, Credito.EstadoCredito.PAGADO, LocalDate.now().minusMonths(4));

        Map<String, double[]> f = excelCartera(List.of(pagado), FILTRO_CLIENTE);

        assertThat(f)
                .doesNotContainKey("Capital pendiente de la cartera vigente")
                .doesNotContainKey("PAR 30")
                .doesNotContainKey("1–30 días");
    }

    @Test
    void calidadDeCartera_sinFiltrosTomaLosNumerosDelDashboard() {
        when(dashboardService.obtenerResumen()).thenReturn(respuestaDelDashboard());

        Map<String, double[]> f = excelCartera(List.of(), SIN_FILTROS);

        assertThat(f).containsKey("Calidad de cartera");   // sin el sufijo "(créditos listados)"
        assertThat(f.get("Capital pendiente de la cartera vigente")[0]).isCloseTo(12982.98, within(0.001));
        assertThat(f.get("PAR 30")[0]).isCloseTo(0.077, within(0.00001));   // 7.7 %
        assertThat(f.get("PAR 30")[1]).isCloseTo(1000.0, within(0.001));
        assertThat(f.get("1–30 días")[0]).isCloseTo(700.28, within(0.001));
        assertThat(f.get("1–30 días")[1]).isEqualTo(2.0);
        assertThat(f.get("31–60 días")[0]).isCloseTo(350.14, within(0.001));
    }

    @Test
    void pdfDeCartera_conFiltrosSeGeneraConLaSeccionDeCalidad() throws Exception {
        Credito alCorriente = credito(16, Credito.EstadoCredito.APROBADO, LocalDate.now().minusDays(5));
        when(creditoRepository.buscarParaReporte(any(), any(), any(), any()))
                .thenReturn(List.of(creditoEnMoraSinAbonos(), alCorriente));

        byte[] pdf = service.generarReporteCartera(FILTRO_CLIENTE);

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void pdfDeCartera_sinFiltrosSeGeneraConLosIndicadoresDelDashboard() throws Exception {
        when(creditoRepository.buscarParaReporte(any(), any(), any(), any())).thenReturn(List.of());
        when(dashboardService.obtenerResumen()).thenReturn(respuestaDelDashboard());

        byte[] pdf = service.generarReporteCartera(SIN_FILTROS);

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }


    private static final ReporteCarteraFiltros ESTADOS_DE_LA_PANTALLA = new ReporteCarteraFiltros(
        List.of(Credito.EstadoCredito.APROBADO, Credito.EstadoCredito.EN_MORA), null, null, null);

    @Test
    void estadosPorDefecto_sinOtrosFiltros_seTratanComoSinFiltros() {
        when(dashboardService.obtenerResumen()).thenReturn(respuestaDelDashboard());

        Map<String, double[]> f = excelCartera(List.of(), ESTADOS_DE_LA_PANTALLA);

        assertThat(f).containsKey("Créditos activos (0)");
        assertThat(f).containsKey("Calidad de cartera");   // sin el sufijo "(créditos listados)"
        // Los totales salen del dashboard y la etiqueta aclara que incluyen los créditos pagados
        assertThat(f.get("Total prestado (toda la cartera, incluye pagados)")[0]).isCloseTo(5000.0, within(0.001));
        assertThat(f.get("En mora")[0]).isCloseTo(1050.42, within(0.001));
    }

    @Test
    void estadosPorDefectoConUnCliente_siguenSiendoFiltrados() {
        // dashboardService no tiene respuesta: si el servicio lo llamara, la prueba fallaría
        Map<String, double[]> f = excelCartera(List.of(), FILTRO_CLIENTE);

        assertThat(f).containsKey("Créditos filtrados (0)");
        assertThat(f).containsKey("Total prestado (créditos listados)");
    }
}
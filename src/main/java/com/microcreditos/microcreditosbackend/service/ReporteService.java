package com.microcreditos.microcreditosbackend.service;

// ✅ Pon esto
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

// ✅ Y pon solo lo que realmente usas
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFColor;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import com.microcreditos.microcreditosbackend.dto.DashboardResponse;
import com.microcreditos.microcreditosbackend.dto.ParDTO;
import com.microcreditos.microcreditosbackend.dto.ReporteCarteraFiltros;
import com.microcreditos.microcreditosbackend.dto.TramoAgingDTO;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.Pago;
import com.microcreditos.microcreditosbackend.repository.CreditoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class ReporteService {

    @Autowired
    private CreditoRepository creditoRepository;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private CronogramaService cronogramaService;

    @Autowired
    private MoraCarteraService moraCarteraService;

    private static final List<Credito.EstadoCredito> ESTADOS_DEFAULT =
            ReporteCarteraFiltros.ESTADOS_PREDETERMINADOS;

    // Créditos que pueden tener interés moratorio: un crédito PAGADO también puede quedar debiéndolo.
    private static final List<Credito.EstadoCredito> ESTADOS_CON_MORA =
            List.of(Credito.EstadoCredito.APROBADO, Credito.EstadoCredito.EN_MORA, Credito.EstadoCredito.PAGADO);

    private static final Color TEAL = new Color(15, 118, 110);
    private static final byte[] TEAL_RGB = {15, 118, 110};
    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Font FONT_TITULO = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
    private static final Font FONT_SUBTITULO = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.GRAY);
    private static final Font FONT_RESUMEN_LABEL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font FONT_RESUMEN_VALOR = FontFactory.getFont(FontFactory.HELVETICA, 10);
    private static final Font FONT_TABLA_HEADER = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
    private static final Font FONT_TABLA_CELDA = FontFactory.getFont(FontFactory.HELVETICA, 9);

    // ===================== PDF — CARTERA =====================

    public byte[] generarReporteCartera(ReporteCarteraFiltros filtros) throws DocumentException {
        ReporteData datos = obtenerDatosReporte(filtros);

        Document document = new Document(PageSize.A4, 36, 36, 54, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(document, out);
        document.open();

        document.add(new Paragraph("Reporte de cartera", FONT_TITULO));
        document.add(new Paragraph("Generado el " + LocalDate.now().format(FORMATO_FECHA), FONT_SUBTITULO));
        document.add(Chunk.NEWLINE);

        agregarResumenPdf(document, datos.resumen(), !filtros.esVistaPredeterminada());
        agregarCalidadCarteraPdf(document, datos.resumen(), !filtros.esVistaPredeterminada());
        document.add(Chunk.NEWLINE);
        agregarTablaCreditosPdf(document, datos.creditos(), datos.mora(), filtros.esVistaPredeterminada() ? "Créditos activos" : "Créditos filtrados");

        document.close();
        return out.toByteArray();
    }

    private void agregarResumenPdf(Document document, ResumenPdf resumen, boolean filtrado) throws DocumentException {
        PdfPTable tabla = new PdfPTable(2);
        tabla.setWidthPercentage(100);
        tabla.setWidths(new float[]{1f, 1f});

       
        // Con filtros, el resumen suma solo los créditos listados; en la vista predeterminada, toda la
        // cartera desembolsada (como el dashboard), que incluye los créditos ya pagados.
        String sufijo = filtrado ? " (créditos listados)" : " (toda la cartera, incluye pagados)";
        agregarFilaResumenPdf(tabla, "Total prestado" + sufijo, formatearMoneda(resumen.totalPrestado()));
        agregarFilaResumenPdf(tabla, "Total cobrado" + sufijo, formatearMoneda(resumen.totalCobrado()));
        agregarFilaResumenPdf(tabla, "Créditos vigentes", String.valueOf(resumen.creditosVigentes()));
        agregarFilaResumenPdf(tabla, "En mora", formatearMoneda(resumen.totalEnMora())
                + " (" + resumen.creditosEnMora() + (resumen.creditosEnMora() == 1 ? " crédito" : " créditos") + ")");
        agregarFilaResumenPdf(tabla, "Interés moratorio por cobrar", formatearMoneda(resumen.saldoMora()));

        document.add(tabla);
    }

    /**
     * PAR 30/60/90 y aging de la cartera vigente. Son los mismos números del dashboard (los calcula
     * IndicadoresRiesgoCartera). Con filtros se calculan solo sobre los créditos listados. Si no hay
     * cartera vigente (por ejemplo, el filtro solo trae créditos pagados) no hay nada que medir y la
     * sección no se imprime, igual que en el dashboard.
     */
    private void agregarCalidadCarteraPdf(Document document, ResumenPdf resumen, boolean filtrado) throws DocumentException {
        if (resumen.saldoCapitalVigente().signum() <= 0) return;

        document.add(Chunk.NEWLINE);
        document.add(new Paragraph("Calidad de cartera" + (filtrado ? " (créditos listados)" : ""), FONT_RESUMEN_LABEL));
        document.add(new Paragraph("Capital pendiente de la cartera vigente: "
                + formatearMoneda(resumen.saldoCapitalVigente()), FONT_SUBTITULO));
        document.add(Chunk.NEWLINE);

        PdfPTable tablaPar = new PdfPTable(4);
        tablaPar.setWidthPercentage(100);
        tablaPar.setWidths(new float[]{1f, 1f, 1.4f, 1f});
        for (String header : new String[]{"Indicador", "Porcentaje", "Capital en riesgo", "Créditos"}) {
            agregarEncabezadoPdf(tablaPar, header);
        }
        for (ParDTO par : resumen.par()) {
            agregarCeldaPdf(tablaPar, "PAR " + par.dias());
            agregarCeldaPdf(tablaPar, par.porcentaje().toPlainString() + "%");
            agregarCeldaPdf(tablaPar, formatearMoneda(par.capitalEnRiesgo()));
            agregarCeldaPdf(tablaPar, String.valueOf(par.creditos()));
        }
        document.add(tablaPar);
        document.add(Chunk.NEWLINE);

        PdfPTable tablaAging = new PdfPTable(3);
        tablaAging.setWidthPercentage(100);
        tablaAging.setWidths(new float[]{1.4f, 1.4f, 1f});
        for (String header : new String[]{"Aging (días de atraso)", "Monto por cobrar", "Créditos"}) {
            agregarEncabezadoPdf(tablaAging, header);
        }
        for (TramoAgingDTO tramo : resumen.aging()) {
            agregarCeldaPdf(tablaAging, tramo.etiqueta());
            agregarCeldaPdf(tablaAging, formatearMoneda(tramo.monto()));
            agregarCeldaPdf(tablaAging, String.valueOf(tramo.creditos()));
        }
        document.add(tablaAging);

        document.add(new Paragraph("PAR N: capital pendiente de los créditos con alguna cuota vencida hace más de N días, "
                + "entre el capital pendiente de la cartera vigente. Aging: lo que falta por cobrar de cada cuota vencida, "
                + "según sus días de atraso; un crédito con cuotas de distinta antigüedad cuenta en cada tramo.", FONT_SUBTITULO));
    }

    private void agregarFilaResumenPdf(PdfPTable tabla, String etiqueta, String valor) {
        PdfPCell celdaEtiqueta = new PdfPCell(new Phrase(etiqueta, FONT_RESUMEN_LABEL));
        celdaEtiqueta.setBorder(Rectangle.NO_BORDER);
        celdaEtiqueta.setPaddingBottom(4);
        tabla.addCell(celdaEtiqueta);

        PdfPCell celdaValor = new PdfPCell(new Phrase(valor, FONT_RESUMEN_VALOR));
        celdaValor.setBorder(Rectangle.NO_BORDER);
        celdaValor.setPaddingBottom(4);
        tabla.addCell(celdaValor);
    }

    private void agregarTablaCreditosPdf(Document document, List<Credito> creditos,
                                         Map<Long, MoraCarteraService.MoraResumen> mora, String titulo) throws DocumentException {
        document.add(new Paragraph(titulo + " (" + creditos.size() + ")", FONT_RESUMEN_LABEL));
        document.add(Chunk.NEWLINE);

        PdfPTable tabla = new PdfPTable(9);
        tabla.setWidthPercentage(100);
        tabla.setWidths(new float[]{1.6f, 1.1f, 0.7f, 0.8f, 1.2f, 1.1f, 1.1f, 1.2f, 1.2f});

        for (String header : new String[]{"Cliente", "Monto", "Plazo", "Tasa", "Estado", "Abonado", "Saldo", "Interés por cobrar", "Cobrador"}) {
            agregarEncabezadoPdf(tabla, header);
        }

        for (Credito credito : creditos) {
            BigDecimal totalAbonado = sumaPagos(credito);
            BigDecimal saldo = saldoPendiente(credito, totalAbonado);

            agregarCeldaPdf(tabla, credito.getCliente().getNombreCompleto());
            agregarCeldaPdf(tabla, formatearMoneda(credito.getMontoSolicitado()));
            agregarCeldaPdf(tabla, credito.getPlazoMeses() + " m");
            agregarCeldaPdf(tabla, credito.getTasaInteresMensual() + "%");
            agregarCeldaPdf(tabla, credito.getEstado().name());
            agregarCeldaPdf(tabla, formatearMoneda(totalAbonado));
            agregarCeldaPdf(tabla, formatearMoneda(saldo));
            agregarCeldaPdf(tabla, formatearMoneda(interesPorCobrar(mora, credito)));
            agregarCeldaPdf(tabla, nombreCobrador(credito));
        }

        document.add(tabla);
    }

    private void agregarEncabezadoPdf(PdfPTable tabla, String texto) {
        PdfPCell celda = new PdfPCell(new Phrase(texto, FONT_TABLA_HEADER));
        celda.setBackgroundColor(TEAL);
        celda.setPadding(5);
        tabla.addCell(celda);
    }

    private void agregarCeldaPdf(PdfPTable tabla, String texto) {
        PdfPCell celda = new PdfPCell(new Phrase(texto, FONT_TABLA_CELDA));
        celda.setPadding(5);
        tabla.addCell(celda);
    }

    // ===================== EXCEL — CARTERA =====================

    public byte[] generarReporteCarteraExcel(ReporteCarteraFiltros filtros) throws IOException {
        ReporteData datos = obtenerDatosReporte(filtros);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Cartera");

            CellStyle estiloTitulo = crearEstiloTitulo(workbook);
            CellStyle estiloEtiqueta = crearEstiloEtiqueta(workbook);
            CellStyle estiloHeaderTabla = crearEstiloHeaderTabla(workbook);
            CellStyle estiloMoneda = crearEstiloMoneda(workbook);
            CellStyle estiloPorcentaje = crearEstiloPorcentaje(workbook);

            int fila = 0;
            fila = escribirTitulo(sheet, fila, "Reporte de cartera", estiloTitulo);
            fila = escribirResumen(sheet, fila, datos.resumen(), !filtros.esVistaPredeterminada(), estiloEtiqueta, estiloMoneda);
            fila = escribirCalidadCartera(sheet, fila, datos.resumen(), !filtros.esVistaPredeterminada(),
                    estiloEtiqueta, estiloHeaderTabla, estiloMoneda, estiloPorcentaje);
            fila++;
            escribirTablaCreditos(sheet, fila, datos.creditos(), datos.mora(), filtros.esVistaPredeterminada() ? "Créditos activos" : "Créditos filtrados",
                    estiloEtiqueta, estiloHeaderTabla, estiloMoneda);

            ajustarColumnas(sheet, 8);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private int escribirTitulo(Sheet sheet, int fila, String titulo, CellStyle estiloTitulo) {
        Row rowTitulo = sheet.createRow(fila++);
        Cell celdaTitulo = rowTitulo.createCell(0);
        celdaTitulo.setCellValue(titulo);
        celdaTitulo.setCellStyle(estiloTitulo);

        Row rowFecha = sheet.createRow(fila++);
        rowFecha.createCell(0).setCellValue("Generado el " + LocalDate.now().format(FORMATO_FECHA));

        return fila + 1; // deja una fila en blanco
    }

    private int escribirResumen(Sheet sheet, int fila, ResumenPdf resumen, boolean filtrado,
                                CellStyle estiloEtiqueta, CellStyle estiloMoneda) {
        String sufijo = filtrado ? " (créditos listados)" : " (toda la cartera, incluye pagados)";
        fila = escribirFilaResumen(sheet, fila, "Total prestado" + sufijo, resumen.totalPrestado(), estiloEtiqueta, estiloMoneda);
        fila = escribirFilaResumen(sheet, fila, "Total cobrado" + sufijo, resumen.totalCobrado(), estiloEtiqueta, estiloMoneda);

        Row rowVigentes = sheet.createRow(fila++);
        rowVigentes.createCell(0).setCellValue("Créditos vigentes");
        rowVigentes.getCell(0).setCellStyle(estiloEtiqueta);
        rowVigentes.createCell(1).setCellValue(resumen.creditosVigentes());

        fila = escribirFilaResumen(sheet, fila, "En mora", resumen.totalEnMora(), estiloEtiqueta, estiloMoneda);
        Row rowEnMora = sheet.getRow(fila - 1);
        rowEnMora.createCell(2).setCellValue(
                "(" + resumen.creditosEnMora() + (resumen.creditosEnMora() == 1 ? " crédito)" : " créditos)"));

        fila = escribirFilaResumen(sheet, fila, "Interés moratorio por cobrar", resumen.saldoMora(), estiloEtiqueta, estiloMoneda);

        return fila;
    }

    /**
     * Misma información que la sección del PDF: capital pendiente vigente, PAR 30/60/90 y aging.
     * El porcentaje del PAR se guarda como fracción (0.077) con formato 0.0%, así Excel lo muestra
     * como 7.7% y se puede seguir usando en fórmulas.
     */
    private int escribirCalidadCartera(Sheet sheet, int fila, ResumenPdf resumen, boolean filtrado,
                                       CellStyle estiloEtiqueta, CellStyle estiloHeaderTabla,
                                       CellStyle estiloMoneda, CellStyle estiloPorcentaje) {
        if (resumen.saldoCapitalVigente().signum() <= 0) return fila;

        fila++; // fila en blanco
        Row rowTitulo = sheet.createRow(fila++);
        Cell celdaTitulo = rowTitulo.createCell(0);
        celdaTitulo.setCellValue("Calidad de cartera" + (filtrado ? " (créditos listados)" : ""));
        celdaTitulo.setCellStyle(estiloEtiqueta);

        fila = escribirFilaResumen(sheet, fila, "Capital pendiente de la cartera vigente",
                resumen.saldoCapitalVigente(), estiloEtiqueta, estiloMoneda);
        fila++; // fila en blanco

        Row headerPar = sheet.createRow(fila++);
        String[] headersPar = {"Indicador", "Porcentaje", "Capital en riesgo", "Créditos"};
        for (int col = 0; col < headersPar.length; col++) {
            Cell celda = headerPar.createCell(col);
            celda.setCellValue(headersPar[col]);
            celda.setCellStyle(estiloHeaderTabla);
        }
        for (ParDTO par : resumen.par()) {
            Row row = sheet.createRow(fila++);
            row.createCell(0).setCellValue("PAR " + par.dias());

            Cell celdaPorcentaje = row.createCell(1);
            celdaPorcentaje.setCellValue(par.porcentaje().doubleValue() / 100.0);
            celdaPorcentaje.setCellStyle(estiloPorcentaje);

            Cell celdaCapital = row.createCell(2);
            celdaCapital.setCellValue(par.capitalEnRiesgo().doubleValue());
            celdaCapital.setCellStyle(estiloMoneda);

            row.createCell(3).setCellValue(par.creditos());
        }
        fila++; // fila en blanco

        Row headerAging = sheet.createRow(fila++);
        String[] headersAging = {"Aging (días de atraso)", "Monto por cobrar", "Créditos"};
        for (int col = 0; col < headersAging.length; col++) {
            Cell celda = headerAging.createCell(col);
            celda.setCellValue(headersAging[col]);
            celda.setCellStyle(estiloHeaderTabla);
        }
        for (TramoAgingDTO tramo : resumen.aging()) {
            Row row = sheet.createRow(fila++);
            row.createCell(0).setCellValue(tramo.etiqueta());

            Cell celdaMonto = row.createCell(1);
            celdaMonto.setCellValue(tramo.monto().doubleValue());
            celdaMonto.setCellStyle(estiloMoneda);

            row.createCell(2).setCellValue(tramo.creditos());
        }

        return fila;
    }

    private int escribirFilaResumen(Sheet sheet, int fila, String etiqueta, BigDecimal valor,
                                     CellStyle estiloEtiqueta, CellStyle estiloMoneda) {
        Row row = sheet.createRow(fila);
        Cell celdaEtiqueta = row.createCell(0);
        celdaEtiqueta.setCellValue(etiqueta);
        celdaEtiqueta.setCellStyle(estiloEtiqueta);

        Cell celdaValor = row.createCell(1);
        celdaValor.setCellValue(valor.doubleValue());
        celdaValor.setCellStyle(estiloMoneda);

        return fila + 1;
    }

    private void escribirTablaCreditos(Sheet sheet, int fila, List<Credito> creditos,
                                        Map<Long, MoraCarteraService.MoraResumen> mora, String titulo,
                                        CellStyle estiloEtiqueta, CellStyle estiloHeaderTabla, CellStyle estiloMoneda) {
        Row rowTitulo = sheet.createRow(fila++);
        Cell celdaTitulo = rowTitulo.createCell(0);
        celdaTitulo.setCellValue(titulo + " (" + creditos.size() + ")");
        celdaTitulo.setCellStyle(estiloEtiqueta);
        fila++; // fila en blanco

        Row rowHeader = sheet.createRow(fila++);
        String[] headers = {"Cliente", "Monto", "Plazo", "Tasa", "Estado", "Abonado", "Saldo", "Interés por cobrar", "Cobrador"};
        for (int col = 0; col < headers.length; col++) {
            Cell celda = rowHeader.createCell(col);
            celda.setCellValue(headers[col]);
            celda.setCellStyle(estiloHeaderTabla);
        }

        for (Credito credito : creditos) {
            BigDecimal totalAbonado = sumaPagos(credito);
            BigDecimal saldo = saldoPendiente(credito, totalAbonado);

            Row row = sheet.createRow(fila++);
            row.createCell(0).setCellValue(credito.getCliente().getNombreCompleto());

            Cell celdaMonto = row.createCell(1);
            celdaMonto.setCellValue(credito.getMontoSolicitado().doubleValue());
            celdaMonto.setCellStyle(estiloMoneda);

            row.createCell(2).setCellValue(credito.getPlazoMeses() + " m");
            row.createCell(3).setCellValue(credito.getTasaInteresMensual() + "%");
            row.createCell(4).setCellValue(credito.getEstado().name());

            Cell celdaAbonado = row.createCell(5);
            celdaAbonado.setCellValue(totalAbonado.doubleValue());
            celdaAbonado.setCellStyle(estiloMoneda);

            Cell celdaSaldo = row.createCell(6);
            celdaSaldo.setCellValue(saldo.doubleValue());
            celdaSaldo.setCellStyle(estiloMoneda);

            Cell celdaInteres = row.createCell(7);
            celdaInteres.setCellValue(interesPorCobrar(mora, credito).doubleValue());
            celdaInteres.setCellStyle(estiloMoneda);

            row.createCell(8).setCellValue(nombreCobrador(credito));
        }
    }

    // ===================== PDF — PAGOS DE UN CRÉDITO =====================

    public byte[] generarReportePagos(Long creditoId) throws DocumentException {
        Credito credito = obtenerCreditoOrThrow(creditoId);
        List<CronogramaService.FilaCronograma> filas = cronogramaService.generarCronograma(credito);

        Document document = new Document(PageSize.A4, 36, 36, 54, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(document, out);
        document.open();

        document.add(new Paragraph("Reporte de pagos — Crédito #" + credito.getId(), FONT_TITULO));
        document.add(new Paragraph("Generado el " + LocalDate.now().format(FORMATO_FECHA), FONT_SUBTITULO));
        document.add(Chunk.NEWLINE);

        agregarResumenPagosPdf(document, credito, filas);
        document.add(Chunk.NEWLINE);
        agregarTablaPagosPdf(document, filas);

        document.close();
        return out.toByteArray();
    }

    private void agregarResumenPagosPdf(Document document, Credito credito, List<CronogramaService.FilaCronograma> filas)
            throws DocumentException {
        BigDecimal totalPagado = sumaPagos(credito);
        BigDecimal totalCredito = credito.getCuotaMensual().multiply(BigDecimal.valueOf(credito.getPlazoMeses()));
        BigDecimal saldoPendienteTotal = totalCredito.subtract(totalPagado).max(BigDecimal.ZERO);

        PdfPTable tabla = new PdfPTable(2);
        tabla.setWidthPercentage(100);
        tabla.setWidths(new float[]{1f, 1f});

        agregarFilaResumenPdf(tabla, "Cliente", credito.getCliente().getNombreCompleto());
        agregarFilaResumenPdf(tabla, "Monto solicitado", formatearMoneda(credito.getMontoSolicitado()));
        agregarFilaResumenPdf(tabla, "Plazo", credito.getPlazoMeses() + " meses");
        agregarFilaResumenPdf(tabla, "Tasa mensual", credito.getTasaInteresMensual() + "%");
        agregarFilaResumenPdf(tabla, "Estado del crédito", credito.getEstado().name());
        agregarFilaResumenPdf(tabla, "Total pagado", formatearMoneda(totalPagado));
        agregarFilaResumenPdf(tabla, "Total del crédito (con interés)", formatearMoneda(totalCredito));
        agregarFilaResumenPdf(tabla, "Saldo pendiente", formatearMoneda(saldoPendienteTotal));

        document.add(tabla);
    }

    private void agregarTablaPagosPdf(Document document, List<CronogramaService.FilaCronograma> filas) throws DocumentException {
        document.add(new Paragraph("Cronograma de cuotas (" + filas.size() + ")", FONT_RESUMEN_LABEL));
        document.add(Chunk.NEWLINE);

        PdfPTable tabla = new PdfPTable(7);
        tabla.setWidthPercentage(100);
        tabla.setWidths(new float[]{0.6f, 1.1f, 0.9f, 0.9f, 1.0f, 1.5f, 1.5f});

        for (String header : new String[]{"Cuota", "Vencimiento", "Interés", "Capital", "Abonado", "Pendiente de cuota", "Estado"}) {
            agregarEncabezadoPdf(tabla, header);
        }

        for (CronogramaService.FilaCronograma fila : filas) {
            agregarCeldaPdf(tabla, String.valueOf(fila.numeroCuota()));
            agregarCeldaPdf(tabla, fila.fechaVencimiento().format(FORMATO_FECHA));
            agregarCeldaPdf(tabla, formatearMoneda(fila.interes()));
            agregarCeldaPdf(tabla, formatearMoneda(fila.capital()));
            agregarCeldaPdf(tabla, formatearMoneda(fila.totalAbonado()));
            agregarCeldaPdf(tabla, formatearMoneda(fila.saldoPendienteCuota()));
            agregarCeldaPdf(tabla, textoEstadoCuota(fila.estado()));
        }

        document.add(tabla);
    }

    // ===================== EXCEL — PAGOS DE UN CRÉDITO =====================

    public byte[] generarReportePagosExcel(Long creditoId) throws IOException {
        Credito credito = obtenerCreditoOrThrow(creditoId);
        List<CronogramaService.FilaCronograma> filas = cronogramaService.generarCronograma(credito);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Pagos");

            CellStyle estiloTitulo = crearEstiloTitulo(workbook);
            CellStyle estiloEtiqueta = crearEstiloEtiqueta(workbook);
            CellStyle estiloHeaderTabla = crearEstiloHeaderTabla(workbook);
            CellStyle estiloMoneda = crearEstiloMoneda(workbook);
            CellStyle estiloFecha = crearEstiloFecha(workbook);

            int fila = 0;
            fila = escribirTitulo(sheet, fila, "Reporte de pagos — Crédito #" + credito.getId(), estiloTitulo);
            fila = escribirResumenPagos(sheet, fila, credito, estiloEtiqueta, estiloMoneda);
            fila++;
            escribirTablaPagos(sheet, fila, filas, estiloEtiqueta, estiloHeaderTabla, estiloMoneda, estiloFecha);

            // pon esto
            ajustarColumnas(sheet, 6);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private int escribirResumenPagos(Sheet sheet, int fila, Credito credito, CellStyle estiloEtiqueta, CellStyle estiloMoneda) {
        BigDecimal totalPagado = sumaPagos(credito);
        BigDecimal totalCredito = credito.getCuotaMensual().multiply(BigDecimal.valueOf(credito.getPlazoMeses()));
        BigDecimal saldoPendienteTotal = totalCredito.subtract(totalPagado).max(BigDecimal.ZERO);

        Row rowCliente = sheet.createRow(fila++);
        rowCliente.createCell(0).setCellValue("Cliente");
        rowCliente.getCell(0).setCellStyle(estiloEtiqueta);
        rowCliente.createCell(1).setCellValue(credito.getCliente().getNombreCompleto());

        Row rowEstado = sheet.createRow(fila++);
        rowEstado.createCell(0).setCellValue("Estado del crédito");
        rowEstado.getCell(0).setCellStyle(estiloEtiqueta);
        rowEstado.createCell(1).setCellValue(credito.getEstado().name());

        fila = escribirFilaResumen(sheet, fila, "Monto solicitado", credito.getMontoSolicitado(), estiloEtiqueta, estiloMoneda);
        fila = escribirFilaResumen(sheet, fila, "Total pagado", totalPagado, estiloEtiqueta, estiloMoneda);
        fila = escribirFilaResumen(sheet, fila, "Total del crédito (con interés)", totalCredito, estiloEtiqueta, estiloMoneda);
        fila = escribirFilaResumen(sheet, fila, "Saldo pendiente", saldoPendienteTotal, estiloEtiqueta, estiloMoneda);

        return fila;
    }

    private void escribirTablaPagos(Sheet sheet, int fila, List<CronogramaService.FilaCronograma> filas,
                                     CellStyle estiloEtiqueta, CellStyle estiloHeaderTabla,
                                     CellStyle estiloMoneda, CellStyle estiloFecha) {
        Row rowTitulo = sheet.createRow(fila++);
        Cell celdaTitulo = rowTitulo.createCell(0);
        celdaTitulo.setCellValue("Cronograma de cuotas (" + filas.size() + ")");
        celdaTitulo.setCellStyle(estiloEtiqueta);
        fila++; // fila en blanco

        Row rowHeader = sheet.createRow(fila++);
        String[] headers = {"Cuota", "Vencimiento", "Interés", "Capital", "Abonado", "Pendiente de cuota", "Estado"};
        for (int col = 0; col < headers.length; col++) {
            Cell celda = rowHeader.createCell(col);
            celda.setCellValue(headers[col]);
            celda.setCellStyle(estiloHeaderTabla);
        }

        for (CronogramaService.FilaCronograma filaCronograma : filas) {
            Row row = sheet.createRow(fila++);
            row.createCell(0).setCellValue(filaCronograma.numeroCuota());

            Cell celdaFecha = row.createCell(1);
            celdaFecha.setCellValue(filaCronograma.fechaVencimiento());
            celdaFecha.setCellStyle(estiloFecha);

            Cell celdaInteres = row.createCell(2);
            celdaInteres.setCellValue(redondear2(filaCronograma.interes()));
            celdaInteres.setCellStyle(estiloMoneda);

            Cell celdaCapital = row.createCell(3);
            celdaCapital.setCellValue(redondear2(filaCronograma.capital()));
            celdaCapital.setCellStyle(estiloMoneda);

            Cell celdaAbonado = row.createCell(4);
            celdaAbonado.setCellValue(redondear2(filaCronograma.totalAbonado()));
            celdaAbonado.setCellStyle(estiloMoneda);

            Cell celdaSaldo = row.createCell(5);
            celdaSaldo.setCellValue(redondear2(filaCronograma.saldoPendienteCuota()));
            celdaSaldo.setCellStyle(estiloMoneda);

            row.createCell(6).setCellValue(textoEstadoCuota(filaCronograma.estado()));
        }
    }

    private String textoEstadoCuota(CronogramaService.FilaCronograma.EstadoCuota estado) {
        return switch (estado) {
            case PENDIENTE -> "Pendiente";
            case PARCIAL -> "Parcial";
            case PAGADO -> "Pagado";
            case PAGADO_CON_ATRASO -> "Pagado (con atraso)";
        };
    }

    // ===================== PDF — MORA =====================

    public byte[] generarReporteMora() throws DocumentException {
        ReporteMoraData datos = obtenerDatosMora();

        Document document = new Document(PageSize.A4.rotate(), 36, 36, 54, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(document, out);
        document.open();

        document.add(new Paragraph("Reporte de mora", FONT_TITULO));
        document.add(new Paragraph("Generado el " + LocalDate.now().format(FORMATO_FECHA), FONT_SUBTITULO));
        document.add(Chunk.NEWLINE);

        agregarResumenMoraPdf(document, datos);
        document.add(Chunk.NEWLINE);
        agregarTablaMoraPdf(document, datos.filas());

        if (!datos.interesPorCredito().isEmpty()) {
            document.add(Chunk.NEWLINE);
            agregarTablaInteresPdf(document, datos.interesPorCredito());
        }

        document.close();
        return out.toByteArray();
    }

    private void agregarResumenMoraPdf(Document document, ReporteMoraData datos) throws DocumentException {
        PdfPTable tabla = new PdfPTable(2);
        tabla.setWidthPercentage(100);
        tabla.setWidths(new float[]{1f, 1f});

        agregarFilaResumenPdf(tabla, "Créditos en mora", String.valueOf(datos.creditosEnMora()));
        agregarFilaResumenPdf(tabla, "Monto total en mora", formatearMoneda(datos.totalEnMora()));
        agregarFilaResumenPdf(tabla, "Interés moratorio devengado", formatearMoneda(datos.interes().devengado()));
        agregarFilaResumenPdf(tabla, "Interés moratorio cobrado", formatearMoneda(datos.interes().cobrado()));
        agregarFilaResumenPdf(tabla, "Interés moratorio condonado", formatearMoneda(datos.interes().condonado()));
        agregarFilaResumenPdf(tabla, "Interés moratorio por cobrar", formatearMoneda(datos.interes().saldo()));

        document.add(tabla);
    }

    private void agregarTablaMoraPdf(Document document, List<FilaMora> filas) throws DocumentException {
        document.add(new Paragraph("Cuotas vencidas, ordenadas por días de atraso (" + filas.size() + ")", FONT_RESUMEN_LABEL));
        document.add(Chunk.NEWLINE);

        PdfPTable tabla = new PdfPTable(8);
        tabla.setWidthPercentage(100);
        tabla.setWidths(new float[]{2.0f, 0.9f, 0.8f, 1.2f, 1.2f, 1.3f, 1.3f, 1.4f});

        for (String header : new String[]{"Cliente", "Crédito", "Cuota", "Vencimiento", "Días de atraso", "Monto pendiente", "Interés generado", "Cobrador"}) {
            agregarEncabezadoPdf(tabla, header);
        }

        for (FilaMora fila : filas) {
            agregarCeldaPdf(tabla, fila.clienteNombre());
            agregarCeldaPdf(tabla, "#" + fila.creditoId());
            agregarCeldaPdf(tabla, String.valueOf(fila.numeroCuota()));
            agregarCeldaPdf(tabla, fila.fechaVencimiento().format(FORMATO_FECHA));
            agregarCeldaPdf(tabla, String.valueOf(fila.diasAtraso()));
            agregarCeldaPdf(tabla, formatearMoneda(fila.montoPendiente()));
            agregarCeldaPdf(tabla, formatearMoneda(fila.interesGenerado()));
            agregarCeldaPdf(tabla, fila.cobrador());
        }

        document.add(tabla);
    }

    private void agregarTablaInteresPdf(Document document, List<FilaInteres> filas) throws DocumentException {
        document.add(new Paragraph("Interés moratorio por cobrar, por crédito (" + filas.size() + ")", FONT_RESUMEN_LABEL));
        document.add(new Paragraph("Incluye créditos ya pagados que quedaron debiendo interés moratorio.", FONT_SUBTITULO));
        document.add(Chunk.NEWLINE);

        PdfPTable tabla = new PdfPTable(7);
        tabla.setWidthPercentage(100);
        tabla.setWidths(new float[]{2.2f, 1.0f, 1.1f, 1.2f, 1.2f, 1.2f, 1.3f});

        for (String header : new String[]{"Cliente", "Crédito", "Estado", "Devengado", "Cobrado", "Condonado", "Por cobrar"}) {
            agregarEncabezadoPdf(tabla, header);
        }

        for (FilaInteres fila : filas) {
            agregarCeldaPdf(tabla, fila.clienteNombre());
            agregarCeldaPdf(tabla, "#" + fila.creditoId());
            agregarCeldaPdf(tabla, textoEstadoCredito(fila.estado()));
            agregarCeldaPdf(tabla, formatearMoneda(fila.devengado()));
            agregarCeldaPdf(tabla, formatearMoneda(fila.cobrado()));
            agregarCeldaPdf(tabla, formatearMoneda(fila.condonado()));
            agregarCeldaPdf(tabla, formatearMoneda(fila.saldo()));
        }

        document.add(tabla);
    }

    // ===================== EXCEL — MORA =====================

    public byte[] generarReporteMoraExcel() throws IOException {
        ReporteMoraData datos = obtenerDatosMora();

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Mora");

            CellStyle estiloTitulo = crearEstiloTitulo(workbook);
            CellStyle estiloEtiqueta = crearEstiloEtiqueta(workbook);
            CellStyle estiloHeaderTabla = crearEstiloHeaderTabla(workbook);
            CellStyle estiloMoneda = crearEstiloMoneda(workbook);
            CellStyle estiloFecha = crearEstiloFecha(workbook);

            int fila = 0;
            fila = escribirTitulo(sheet, fila, "Reporte de mora", estiloTitulo);

            Row rowCreditos = sheet.createRow(fila++);
            rowCreditos.createCell(0).setCellValue("Créditos en mora");
            rowCreditos.getCell(0).setCellStyle(estiloEtiqueta);
            rowCreditos.createCell(1).setCellValue(datos.creditosEnMora());

            fila = escribirFilaResumen(sheet, fila, "Monto total en mora", datos.totalEnMora(), estiloEtiqueta, estiloMoneda);
            fila = escribirFilaResumen(sheet, fila, "Interés moratorio devengado", datos.interes().devengado(), estiloEtiqueta, estiloMoneda);
            fila = escribirFilaResumen(sheet, fila, "Interés moratorio cobrado", datos.interes().cobrado(), estiloEtiqueta, estiloMoneda);
            fila = escribirFilaResumen(sheet, fila, "Interés moratorio condonado", datos.interes().condonado(), estiloEtiqueta, estiloMoneda);
            fila = escribirFilaResumen(sheet, fila, "Interés moratorio por cobrar", datos.interes().saldo(), estiloEtiqueta, estiloMoneda);
            fila++;

            fila = escribirTablaMora(sheet, fila, datos.filas(), estiloEtiqueta, estiloHeaderTabla, estiloMoneda, estiloFecha);

            if (!datos.interesPorCredito().isEmpty()) {
                fila++;
                escribirTablaInteres(sheet, fila, datos.interesPorCredito(), estiloEtiqueta, estiloHeaderTabla, estiloMoneda);
            }

            // pon esto
            ajustarColumnas(sheet, 7);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private int escribirTablaMora(Sheet sheet, int fila, List<FilaMora> filas,
                                   CellStyle estiloEtiqueta, CellStyle estiloHeaderTabla,
                                   CellStyle estiloMoneda, CellStyle estiloFecha) {
        Row rowTitulo = sheet.createRow(fila++);
        Cell celdaTitulo = rowTitulo.createCell(0);
        celdaTitulo.setCellValue("Cuotas vencidas, ordenadas por días de atraso (" + filas.size() + ")");
        celdaTitulo.setCellStyle(estiloEtiqueta);
        fila++; // fila en blanco

        Row rowHeader = sheet.createRow(fila++);
        String[] headers = {"Cliente", "Crédito", "Cuota", "Vencimiento", "Días de atraso", "Monto pendiente", "Interés generado", "Cobrador"};
        for (int col = 0; col < headers.length; col++) {
            Cell celda = rowHeader.createCell(col);
            celda.setCellValue(headers[col]);
            celda.setCellStyle(estiloHeaderTabla);
        }

        for (FilaMora filaMora : filas) {
            Row row = sheet.createRow(fila++);
            row.createCell(0).setCellValue(filaMora.clienteNombre());
            row.createCell(1).setCellValue("#" + filaMora.creditoId());
            row.createCell(2).setCellValue(filaMora.numeroCuota());

            Cell celdaFecha = row.createCell(3);
            celdaFecha.setCellValue(filaMora.fechaVencimiento());
            celdaFecha.setCellStyle(estiloFecha);

            row.createCell(4).setCellValue(filaMora.diasAtraso());

            Cell celdaMonto = row.createCell(5);
            celdaMonto.setCellValue(filaMora.montoPendiente().doubleValue());
            celdaMonto.setCellStyle(estiloMoneda);

            Cell celdaInteres = row.createCell(6);
            celdaInteres.setCellValue(filaMora.interesGenerado().doubleValue());
            celdaInteres.setCellStyle(estiloMoneda);

            row.createCell(7).setCellValue(filaMora.cobrador());
        }
        return fila;
    }

    private void escribirTablaInteres(Sheet sheet, int fila, List<FilaInteres> filas,
                                       CellStyle estiloEtiqueta, CellStyle estiloHeaderTabla, CellStyle estiloMoneda) {
        Row rowTitulo = sheet.createRow(fila++);
        Cell celdaTitulo = rowTitulo.createCell(0);
        celdaTitulo.setCellValue("Interés moratorio por cobrar, por crédito (" + filas.size() + ")");
        celdaTitulo.setCellStyle(estiloEtiqueta);

        Row rowNota = sheet.createRow(fila++);
        rowNota.createCell(0).setCellValue("Incluye créditos ya pagados que quedaron debiendo interés moratorio.");
        fila++; // fila en blanco

        Row rowHeader = sheet.createRow(fila++);
        String[] headers = {"Cliente", "Crédito", "Estado", "Devengado", "Cobrado", "Condonado", "Por cobrar"};
        for (int col = 0; col < headers.length; col++) {
            Cell celda = rowHeader.createCell(col);
            celda.setCellValue(headers[col]);
            celda.setCellStyle(estiloHeaderTabla);
        }

        for (FilaInteres filaInteres : filas) {
            Row row = sheet.createRow(fila++);
            row.createCell(0).setCellValue(filaInteres.clienteNombre());
            row.createCell(1).setCellValue("#" + filaInteres.creditoId());
            row.createCell(2).setCellValue(textoEstadoCredito(filaInteres.estado()));

            BigDecimal[] montos = {filaInteres.devengado(), filaInteres.cobrado(), filaInteres.condonado(), filaInteres.saldo()};
            for (int i = 0; i < montos.length; i++) {
                Cell celda = row.createCell(3 + i);
                celda.setCellValue(montos[i].doubleValue());
                celda.setCellStyle(estiloMoneda);
            }
        }
    }

    // ===================== Estilos Excel compartidos =====================

    private CellStyle crearEstiloTitulo(Workbook workbook) {
        CellStyle estilo = workbook.createCellStyle();
        org.apache.poi.ss.usermodel.Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        estilo.setFont(font);
        return estilo;
    }

    private CellStyle crearEstiloEtiqueta(Workbook workbook) {
        CellStyle estilo = workbook.createCellStyle();
        org.apache.poi.ss.usermodel.Font font = workbook.createFont();
        font.setBold(true);
        estilo.setFont(font);
        return estilo;
    }

    private CellStyle crearEstiloHeaderTabla(Workbook workbook) {
        CellStyle estilo = workbook.createCellStyle();
        org.apache.poi.ss.usermodel.Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        estilo.setFont(font);
        estilo.setFillForegroundColor(new XSSFColor(TEAL_RGB, null));
        estilo.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return estilo;
    }

    private CellStyle crearEstiloMoneda(Workbook workbook) {
        CellStyle estilo = workbook.createCellStyle();
        estilo.setDataFormat(workbook.createDataFormat().getFormat("$#,##0.00"));
        return estilo;
    }

    private CellStyle crearEstiloPorcentaje(Workbook workbook) {
        CellStyle estilo = workbook.createCellStyle();
        estilo.setDataFormat(workbook.createDataFormat().getFormat("0.0%"));
        return estilo;
    }

    private CellStyle crearEstiloFecha(Workbook workbook) {
        CellStyle estilo = workbook.createCellStyle();
        estilo.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
        return estilo;
    }

    // ===================== Compartido =====================

    /** Redondea a centavos antes de guardar en Excel, igual que lo que muestra el PDF. */
    private double redondear2(BigDecimal monto) {
        return monto.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }


    /** autoSizeColumn deja las columnas justas con negritas y formato de moneda; se agrega holgura de 6 caracteres. */
    private void ajustarColumnas(Sheet sheet, int ultimaColumna) {
        for (int col = 0; col <= ultimaColumna; col++) {
            sheet.autoSizeColumn(col);
            int ancho = sheet.getColumnWidth(col) + 6 * 256;
            sheet.setColumnWidth(col, Math.min(ancho, 255 * 256)); // 255 caracteres es el máximo de Excel
        }
    }


    /** Criterio único de "cuota vencida sin pagar": vence antes de hoy y está PENDIENTE o PARCIAL. */
    private boolean esVencidaSinPagar(CronogramaService.FilaCronograma fc, LocalDate hoy) {
        return fc.fechaVencimiento().isBefore(hoy)
                && (fc.estado() == CronogramaService.FilaCronograma.EstadoCuota.PENDIENTE
                || fc.estado() == CronogramaService.FilaCronograma.EstadoCuota.PARCIAL);
    }

    private ReporteData obtenerDatosReporte(ReporteCarteraFiltros filtros) {
        List<Credito.EstadoCredito> estados = resolverEstados(filtros);
        List<Credito> creditos = creditoRepository.buscarParaReporte(
                estados, filtros.clienteId(), filtros.fechaDesde(), filtros.fechaHasta());

        // Interés moratorio de cada crédito listado, calculado para todos de una sola vez.
        Map<Long, MoraCarteraService.MoraResumen> mora = moraCarteraService.calcular(creditos, LocalDate.now());

        ResumenPdf resumen = filtros.esVistaPredeterminada()
                ? ResumenPdf.desde(dashboardService.obtenerResumen())
                : calcularResumen(creditos, mora);

        return new ReporteData(creditos, mora, resumen);
    }

    private List<Credito.EstadoCredito> resolverEstados(ReporteCarteraFiltros filtros) {
        return (filtros.estados() == null || filtros.estados().isEmpty()) ? ESTADOS_DEFAULT : filtros.estados();
    }

    private ResumenPdf calcularResumen(List<Credito> creditos, Map<Long, MoraCarteraService.MoraResumen> mora) {
        LocalDate hoy = LocalDate.now();
        BigDecimal totalPrestado = BigDecimal.ZERO;
        BigDecimal totalCobrado = BigDecimal.ZERO;
        BigDecimal totalEnMora = BigDecimal.ZERO;
        BigDecimal saldoMora = BigDecimal.ZERO;
        long vigentes = 0;
        long enMora = 0;

        // PAR y aging de los créditos vigentes listados (mismo cálculo que el dashboard).
        IndicadoresRiesgoCartera riesgo = new IndicadoresRiesgoCartera(hoy);

        for (Credito credito : creditos) {
            totalPrestado = totalPrestado.add(credito.getMontoSolicitado());
            totalCobrado = totalCobrado.add(sumaPagos(credito));
            saldoMora = saldoMora.add(interesPorCobrar(mora, credito));

            // Vigente = APROBADO o EN_MORA (un PAGADO suma al prestado, al cobrado y al interés, pero no es vigente)
            boolean esVigente = credito.getEstado() == Credito.EstadoCredito.APROBADO
                    || credito.getEstado() == Credito.EstadoCredito.EN_MORA;
            if (!esVigente) continue;
            vigentes++;

            // En mora = lo que falta de las cuotas ya vencidas (mismo criterio que el dashboard)
            List<CronogramaService.FilaCronograma> cronograma = cronogramaService.generarCronograma(credito);
            List<CronogramaService.FilaCronograma> vencidas = cronograma.stream()
                    .filter(fc -> esVencidaSinPagar(fc, hoy))
                    .toList();

            // El aging y el PAR usan las mismas cuotas vencidas que "En mora", así las cifras cuadran.
            riesgo.agregar(credito.getMontoSolicitado(), cronograma,
                    vencidas.stream().map(CronogramaService.FilaCronograma::numeroCuota).toList());

            if (!vencidas.isEmpty()) {
                enMora++;
                totalEnMora = totalEnMora.add(vencidas.stream()
                        .map(CronogramaService.FilaCronograma::saldoPendienteCuota)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
            }
        }

        return new ResumenPdf(totalPrestado, totalCobrado, vigentes, totalEnMora, enMora, saldoMora,
                riesgo.capitalVigente(), riesgo.aging(), riesgo.par());
    }

    /**
     * Reporte de mora. Reúne dos cosas:
     *
     * 1) Las cuotas vencidas y sin completar de los créditos EN_MORA (mismo criterio que
     *    cuotasVencidasSinPagar: fechaVencimiento antes de hoy y estado PENDIENTE o PARCIAL),
     *    ordenadas por días de atraso descendente, que es el orden en que un cobrador debería
     *    trabajarlas. Cada cuota lleva el interés moratorio que ha generado y el cobrador
     *    asignado al crédito (desde V4).
     *
     * 2) El interés moratorio de TODA la cartera (APROBADO, EN_MORA y PAGADO), crédito por crédito,
     *    porque un crédito ya pagado puede seguir debiendo mora. Usa MoraCarteraService, el mismo
     *    cálculo del dashboard, así que las cifras coinciden.
     */
    private ReporteMoraData obtenerDatosMora() {
        LocalDate hoy = LocalDate.now();
        List<Credito> creditos = creditoRepository.findByEstadoIn(ESTADOS_CON_MORA);
        Map<Long, MoraCarteraService.MoraResumen> mora = moraCarteraService.calcular(creditos, hoy);

        List<FilaMora> filas = new ArrayList<>();
        int creditosEnMora = 0;

        for (Credito credito : creditos) {
            if (credito.getEstado() != Credito.EstadoCredito.EN_MORA) continue;
            creditosEnMora++;

            MoraCarteraService.MoraResumen moraCredito = mora.get(credito.getId());
            List<CronogramaService.FilaCronograma> cronograma = cronogramaService.generarCronograma(credito);
            for (CronogramaService.FilaCronograma fc : cronograma) {
                if (esVencidaSinPagar(fc, hoy)) {
                    long diasAtraso = ChronoUnit.DAYS.between(fc.fechaVencimiento(), hoy);
                    filas.add(new FilaMora(credito.getCliente().getNombreCompleto(), credito.getId(),
                            fc.numeroCuota(), fc.fechaVencimiento(), diasAtraso, fc.saldoPendienteCuota(),
                            interesDeCuota(moraCredito, fc.numeroCuota()), nombreCobrador(credito)));
                }
            }
        }

        filas.sort(Comparator.comparingLong(FilaMora::diasAtraso).reversed());

        BigDecimal totalEnMora = filas.stream()
                .map(FilaMora::montoPendiente)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Interés por crédito: solo los que todavía tienen saldo por cobrar, de mayor a menor.
        List<FilaInteres> interesPorCredito = new ArrayList<>();
        BigDecimal devengado = BigDecimal.ZERO;
        BigDecimal cobrado = BigDecimal.ZERO;
        BigDecimal condonado = BigDecimal.ZERO;
        BigDecimal saldo = BigDecimal.ZERO;

        for (Credito credito : creditos) {
            MoraCarteraService.MoraResumen m = mora.get(credito.getId());
            if (m == null) continue;

            devengado = devengado.add(m.devengado());
            cobrado = cobrado.add(m.cobrado());
            condonado = condonado.add(m.condonado());
            saldo = saldo.add(m.saldo());

            if (m.saldo().signum() > 0) {
                interesPorCredito.add(new FilaInteres(credito.getCliente().getNombreCompleto(), credito.getId(),
                        credito.getEstado().name(), m.devengado(), m.cobrado(), m.condonado(), m.saldo()));
            }
        }
        interesPorCredito.sort(Comparator.comparing(FilaInteres::saldo).reversed());

        return new ReporteMoraData(filas, creditosEnMora, totalEnMora, interesPorCredito,
                new ResumenInteres(devengado, cobrado, condonado, saldo));
    }

    /** Interés moratorio que ha generado una cuota concreta (0 si todavía no genera, por ejemplo en gracia). */
    private BigDecimal interesDeCuota(MoraCarteraService.MoraResumen mora, int numeroCuota) {
        if (mora == null) return BigDecimal.ZERO;
        return mora.cuotas().stream()
                .filter(c -> c.numeroCuota() == numeroCuota)
                .map(InteresMoratorioService.MoraCuota::interes)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    private String textoEstadoCredito(String estado) {
        return switch (estado) {
            case "EN_MORA" -> "En mora";
            case "PAGADO" -> "Pagado";
            case "APROBADO" -> "Aprobado";
            default -> estado;
        };
    }

    /** Nombre del cobrador asignado al crédito, o un guion si no tiene. */
    private String nombreCobrador(Credito credito) {
        return credito.getCobrador() == null ? "—" : credito.getCobrador().getNombreUsuario();
    }

    /** Interés moratorio por cobrar de un crédito (0 si no admite mora, por ejemplo PENDIENTE o RECHAZADO). */
    private BigDecimal interesPorCobrar(Map<Long, MoraCarteraService.MoraResumen> mora, Credito credito) {
        MoraCarteraService.MoraResumen m = mora.get(credito.getId());
        return m == null ? BigDecimal.ZERO : m.saldo();
    }

    private Credito obtenerCreditoOrThrow(Long creditoId) {
        return creditoRepository.findById(creditoId)
                .orElseThrow(() -> new IllegalArgumentException("Crédito no encontrado con id: " + creditoId));
    }

    private BigDecimal sumaPagos(Credito credito) {
        BigDecimal total = BigDecimal.ZERO;
        if (credito.getPagos() != null) {
            for (Pago pago : credito.getPagos()) {
                total = total.add(pago.getMontoPagado());
            }
        }
        return total;
    }

    private BigDecimal saldoPendiente(Credito credito, BigDecimal abonado) {
        BigDecimal totalCredito = credito.getCuotaMensual().multiply(BigDecimal.valueOf(credito.getPlazoMeses()));
        return totalCredito.subtract(abonado).max(BigDecimal.ZERO);
    }

    private String formatearMoneda(BigDecimal monto) {
        return String.format("$%,.2f", monto);
    }

    private record ReporteData(List<Credito> creditos, Map<Long, MoraCarteraService.MoraResumen> mora, ResumenPdf resumen) {}

    private record ResumenPdf(BigDecimal totalPrestado, BigDecimal totalCobrado,
                               long creditosVigentes, BigDecimal totalEnMora, long creditosEnMora,
                               BigDecimal saldoMora,
                               // Calidad de cartera (punto 40): capital vigente, aging y PAR
                               BigDecimal saldoCapitalVigente, List<TramoAgingDTO> aging, List<ParDTO> par) {
        static ResumenPdf desde(DashboardResponse r) {
            return new ResumenPdf(r.totalPrestado(), r.totalCobrado(), r.creditosVigentes(),
                    r.totalEnMora(), r.creditosEnMora(), r.saldoMora(),
                    r.saldoCapitalVigente() != null ? r.saldoCapitalVigente() : BigDecimal.ZERO,
                    r.aging() != null ? r.aging() : List.of(),
                    r.par() != null ? r.par() : List.of());
        }
    }

    private record FilaMora(String clienteNombre, Long creditoId, int numeroCuota,
                             LocalDate fechaVencimiento, long diasAtraso, BigDecimal montoPendiente,
                             BigDecimal interesGenerado, String cobrador) {}

    private record FilaInteres(String clienteNombre, Long creditoId, String estado,
                                BigDecimal devengado, BigDecimal cobrado, BigDecimal condonado, BigDecimal saldo) {}

    private record ResumenInteres(BigDecimal devengado, BigDecimal cobrado, BigDecimal condonado, BigDecimal saldo) {}

    private record ReporteMoraData(List<FilaMora> filas, int creditosEnMora, BigDecimal totalEnMora,
                                    List<FilaInteres> interesPorCredito, ResumenInteres interes) {}
}
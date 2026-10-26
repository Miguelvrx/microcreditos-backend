package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.MetodoPago;
import com.microcreditos.microcreditosbackend.model.Pago;
import com.microcreditos.microcreditosbackend.repository.PagoRepository;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Genera el recibo de un abono en PDF con formato de ticket de 80 mm.
 *
 * Un recibo corresponde a un ABONO, no a una fila de pago: un abono con derrame
 * crea varias filas (una por cuota) con el mismo abonoRef y sale en un solo recibo.
 * Los pagos anteriores a la migración V2 no tienen abonoRef, así que cada uno cuenta
 * como un abono de una sola fila (misma regla que PagoService y el historial del frontend).
 *
 * El número de recibo es "R-" + el id de la primera fila del abono, con 6 dígitos.
 * El saldo y el estado de cada cuota se calculan "a la fecha de ese abono": se suman
 * todas las filas del crédito (incluidas las anulaciones, que son negativas) con id
 * menor o igual al de la última fila del abono.
 *
 * Desde V4 el recibo muestra el método de pago y, si la hay, la referencia. Todas las filas de un
 * abono comparten ambos datos, así que se leen de la primera fila.
 */
@Service
public class ReciboPagoService {

    private static final float ANCHO = 226.77f;        // 80 mm en puntos
    private static final float MARGEN = 12f;
    private static final float ALTO_MEDICION = 3000f;  // tope de la búsqueda del alto
    private static final float ALTO_MINIMO = 220f;
    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Font FONT_EMPRESA = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
    private static final Font FONT_SUBTITULO = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.GRAY);
    private static final Font FONT_NORMAL = FontFactory.getFont(FontFactory.HELVETICA, 8.5f);
    private static final Font FONT_NEGRITA = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f);
    private static final Font FONT_TOTAL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);

    private final PagoRepository pagoRepository;
    private final JdbcTemplate jdbcTemplate;

    public ReciboPagoService(PagoRepository pagoRepository, JdbcTemplate jdbcTemplate) {
        this.pagoRepository = pagoRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record LineaRecibo(int numeroCuota, BigDecimal aplicado, boolean completa, BigDecimal resta) {}

    public record DatosRecibo(String numero, LocalDate fecha, String cliente, String documento, Long creditoId,
                              List<LineaRecibo> lineas, BigDecimal total, BigDecimal saldoCredito, String recibio,
                              MetodoPago metodoPago, String referenciaPago) {}

    public record ReciboGenerado(String numero, byte[] pdf) {}

    // ===================== Datos =====================

    /**
     * @param pagoId id de cualquier fila del abono.
     * @throws IllegalArgumentException si el pago no existe.
     * @throws IllegalStateException si el pago es una anulación o su abono fue anulado.
     */
    DatosRecibo calcularDatos(Long pagoId) {
        Pago indicado = pagoRepository.findById(pagoId)
                .orElseThrow(() -> new IllegalArgumentException("Pago no encontrado con id: " + pagoId));

        if (indicado.getEstado() == Pago.EstadoPago.ANULACION) {
            throw new IllegalStateException("Ese registro es una anulación y no genera recibo.");
        }

        Credito credito = indicado.getCredito();
        List<Pago> todos = pagoRepository.findByCreditoId(credito.getId());

        Set<Long> anulados = todos.stream()
                .map(Pago::getPagoOriginalId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (anulados.contains(indicado.getId())) {
            throw new IllegalStateException("Ese abono fue anulado y no genera recibo.");
        }

        String clave = claveAbono(indicado);
        List<Pago> filas = todos.stream()
                .filter(p -> p.getEstado() != Pago.EstadoPago.ANULACION)
                .filter(p -> claveAbono(p).equals(clave))
                .sorted(Comparator.comparing(Pago::getId))
                .toList();

        long ultimoId = filas.get(filas.size() - 1).getId();
        BigDecimal cuotaMensual = credito.getCuotaMensual();

        List<LineaRecibo> lineas = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Pago fila : filas) {
            BigDecimal acumulado = todos.stream()
                    .filter(p -> p.getId() <= ultimoId && p.getNumeroCuota().equals(fila.getNumeroCuota()))
                    .map(Pago::getMontoPagado)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean completa = acumulado.compareTo(cuotaMensual) >= 0;
            BigDecimal resta = cuotaMensual.subtract(acumulado).max(BigDecimal.ZERO);

            lineas.add(new LineaRecibo(fila.getNumeroCuota(), fila.getMontoPagado(), completa, resta));
            total = total.add(fila.getMontoPagado());
        }

        BigDecimal pagadoHastaEseAbono = todos.stream()
                .filter(p -> p.getId() <= ultimoId)
                .map(Pago::getMontoPagado)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredito = cuotaMensual.multiply(BigDecimal.valueOf(credito.getPlazoMeses()));
        BigDecimal saldoCredito = totalCredito.subtract(pagadoHastaEseAbono).max(BigDecimal.ZERO);

        Pago primera = filas.get(0);
        return new DatosRecibo(
                "R-" + String.format("%06d", primera.getId()),
                primera.getFechaPago(),
                credito.getCliente().getNombreCompleto(),
                credito.getCliente().getNumeroDocumento(),
                credito.getId(),
                lineas,
                total,
                saldoCredito,
                usuarioQueRegistro(primera.getId()),
                primera.getMetodoPago(),
                primera.getReferenciaPago());
    }

    /** El usuario sale de la auditoría (PAGO_REGISTRADO del pago); si no hay registro, un guion. */
    private String usuarioQueRegistro(Long primerPagoId) {
        List<String> usuarios = jdbcTemplate.queryForList(
                "SELECT usuario FROM auditoria WHERE accion = 'PAGO_REGISTRADO' AND entidad = 'Pago' "
                        + "AND entidad_id = ? ORDER BY id LIMIT 1",
                String.class, primerPagoId);
        return usuarios.isEmpty() ? "—" : usuarios.get(0);
    }

    private static String claveAbono(Pago pago) {
        return pago.getAbonoRef() != null ? pago.getAbonoRef() : "sin-ref-" + pago.getId();
    }

    // ===================== PDF =====================

    public ReciboGenerado generarRecibo(Long pagoId) throws DocumentException {
        DatosRecibo datos = calcularDatos(pagoId);
        float alto = buscarAltoMinimo(datos);
        return new ReciboGenerado(datos.numero(), renderizar(datos, alto).pdf());
    }

    private record Render(byte[] pdf, int paginas) {}

    /**
     * Busca el alto más pequeño con el que el recibo cabe en UNA sola página, para que el ticket
     * no lleve papel en blanco. No se mide el contenido: se prueba. Cada intento genera el PDF y
     * revisa cuántas páginas resultaron (búsqueda binaria entre ALTO_MINIMO y ALTO_MEDICION).
     */
    private float buscarAltoMinimo(DatosRecibo datos) throws DocumentException {
        float bajo = ALTO_MINIMO;
        float alto = ALTO_MEDICION;

        if (renderizar(datos, bajo).paginas() == 1) {
            return bajo;
        }
        while (alto - bajo > 2f) {
            float medio = (alto + bajo) / 2f;
            if (renderizar(datos, medio).paginas() == 1) {
                alto = medio;   // cabe: se puede probar con menos
            } else {
                bajo = medio;   // no cabe: hace falta más alto
            }
        }
        return alto + 4f;       // holgura para que nunca quede justo
    }

    private Render renderizar(DatosRecibo datos, float alto) throws DocumentException {
        Document document = new Document(new Rectangle(ANCHO, alto), MARGEN, MARGEN, MARGEN, MARGEN);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter writer = PdfWriter.getInstance(document, out);
        document.open();

        agregarContenido(document, datos);

        int paginas = writer.getPageNumber();
        document.close();
        return new Render(out.toByteArray(), paginas);
    }

    private void agregarContenido(Document document, DatosRecibo datos) throws DocumentException {
        document.add(centrado("Microcréditos", FONT_EMPRESA));
        document.add(centrado("RECIBO DE PAGO", FONT_SUBTITULO));
        document.add(separador());

        PdfPTable cabecera = tabla(new float[]{1f, 1f});
        cabecera.addCell(celda("No. " + datos.numero(), FONT_NEGRITA, Element.ALIGN_LEFT));
        cabecera.addCell(celda(datos.fecha().format(FORMATO_FECHA), FONT_NORMAL, Element.ALIGN_RIGHT));
        document.add(cabecera);
        document.add(separador());

        PdfPTable cliente = tabla(new float[]{0.9f, 1.6f});
        cliente.addCell(celda("Cliente:", FONT_NEGRITA, Element.ALIGN_LEFT));
        cliente.addCell(celda(datos.cliente(), FONT_NORMAL, Element.ALIGN_LEFT));
        cliente.addCell(celda("Documento:", FONT_NEGRITA, Element.ALIGN_LEFT));
        cliente.addCell(celda(datos.documento(), FONT_NORMAL, Element.ALIGN_LEFT));
        cliente.addCell(celda("Crédito:", FONT_NEGRITA, Element.ALIGN_LEFT));
        cliente.addCell(celda("#" + datos.creditoId(), FONT_NORMAL, Element.ALIGN_LEFT));
        cliente.addCell(celda("Método:", FONT_NEGRITA, Element.ALIGN_LEFT));
        cliente.addCell(celda(
                (datos.metodoPago() == null ? MetodoPago.EFECTIVO : datos.metodoPago()).etiqueta(),
                FONT_NORMAL, Element.ALIGN_LEFT));
        if (datos.referenciaPago() != null) {
            cliente.addCell(celda("Referencia:", FONT_NEGRITA, Element.ALIGN_LEFT));
            cliente.addCell(celda(datos.referenciaPago(), FONT_NORMAL, Element.ALIGN_LEFT));
        }
        document.add(cliente);
        document.add(separador());

        PdfPTable desglose = tabla(new float[]{1f, 1.1f, 1.3f});
        desglose.addCell(celda("Cuota", FONT_NEGRITA, Element.ALIGN_LEFT));
        desglose.addCell(celda("Aplicado", FONT_NEGRITA, Element.ALIGN_RIGHT));
        desglose.addCell(celda("Estado", FONT_NEGRITA, Element.ALIGN_RIGHT));
        for (LineaRecibo linea : datos.lineas()) {
            desglose.addCell(celda("Cuota " + linea.numeroCuota(), FONT_NORMAL, Element.ALIGN_LEFT));
            desglose.addCell(celda(moneda(linea.aplicado()), FONT_NORMAL, Element.ALIGN_RIGHT));
            desglose.addCell(celda(linea.completa() ? "Completa" : "Resta " + moneda(linea.resta()),
                    FONT_NORMAL, Element.ALIGN_RIGHT));
        }
        document.add(desglose);
        document.add(separador());

        PdfPTable totales = tabla(new float[]{1.4f, 1f});
        totales.addCell(celda("TOTAL RECIBIDO", FONT_TOTAL, Element.ALIGN_LEFT));
        totales.addCell(celda(moneda(datos.total()), FONT_TOTAL, Element.ALIGN_RIGHT));
        totales.addCell(celda("Saldo del crédito", FONT_NORMAL, Element.ALIGN_LEFT));
        totales.addCell(celda(moneda(datos.saldoCredito()), FONT_NORMAL, Element.ALIGN_RIGHT));
        document.add(totales);
        document.add(separador());

        document.add(centrado("Recibió: " + datos.recibio(), FONT_NORMAL));
        Paragraph gracias = centrado("Gracias por su pago", FONT_SUBTITULO);
        gracias.setSpacingBefore(6f);
        document.add(gracias);
    }

    // ===================== Helpers de dibujo =====================

    private Paragraph centrado(String texto, Font font) {
        Paragraph p = new Paragraph(texto, font);
        p.setAlignment(Element.ALIGN_CENTER);
        return p;
    }

    private PdfPTable tabla(float[] anchos) throws DocumentException {
        PdfPTable tabla = new PdfPTable(anchos.length);
        tabla.setWidthPercentage(100);
        tabla.setWidths(anchos);
        return tabla;
    }

    private PdfPCell celda(String texto, Font font, int alineacion) {
        PdfPCell celda = new PdfPCell(new Phrase(texto, font));
        celda.setBorder(Rectangle.NO_BORDER);
        celda.setHorizontalAlignment(alineacion);
        celda.setPaddingTop(1.5f);
        celda.setPaddingBottom(1.5f);
        celda.setPaddingLeft(0f);
        celda.setPaddingRight(0f);
        return celda;
    }

    /** Línea horizontal fina: una tabla de una celda vacía con solo el borde superior. */
    private PdfPTable separador() {
        PdfPTable tabla = new PdfPTable(1);
        tabla.setWidthPercentage(100);
        tabla.setSpacingBefore(2f);

        PdfPCell celda = new PdfPCell(new Phrase(""));
        celda.setBorder(Rectangle.TOP);
        celda.setBorderWidth(0.5f);
        celda.setBorderColor(Color.GRAY);
        celda.setFixedHeight(5f);
        celda.setPadding(0f);
        tabla.addCell(celda);
        return tabla;
    }

    private String moneda(BigDecimal monto) {
        return String.format(Locale.US, "$%,.2f", monto);
    }
}
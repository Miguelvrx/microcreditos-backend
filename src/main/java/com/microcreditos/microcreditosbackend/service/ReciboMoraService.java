package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.MovimientoMora;
import com.microcreditos.microcreditosbackend.model.Pago;
import com.microcreditos.microcreditosbackend.model.TipoMovimientoMora;
import com.microcreditos.microcreditosbackend.repository.CreditoRepository;
import com.microcreditos.microcreditosbackend.repository.MovimientoMoraRepository;
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
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Recibo en PDF (ticket de 80 mm) de UN cobro de interés moratorio. Las condonaciones no generan
 * recibo: no es dinero recibido.
 *
 * Número de recibo: "M-" + id del movimiento con 6 dígitos (la M evita confundirlo con los "R-" de las
 * cuotas). El saldo de mora se calcula "a la fecha de ese cobro": interés devengado a la fecha del
 * cobro menos todo lo cobrado y condonado hasta ese movimiento (ids menores o iguales). Los pagos
 * posteriores o las anulaciones posteriores no cambian el devengado de esa fecha.
 * Quien recibió el cobro sale del propio movimiento (usuario guardado al cobrar).
 */
@Service
public class ReciboMoraService {

    private static final float ANCHO = 226.77f;        // 80 mm en puntos
    private static final float MARGEN = 12f;
    private static final float ALTO_MEDICION = 3000f;  // tope de la búsqueda del alto
    private static final float ALTO_MINIMO = 200f;
    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Font FONT_EMPRESA = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
    private static final Font FONT_SUBTITULO = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.GRAY);
    private static final Font FONT_NORMAL = FontFactory.getFont(FontFactory.HELVETICA, 8.5f);
    private static final Font FONT_NEGRITA = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f);
    private static final Font FONT_TOTAL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);

    private final MovimientoMoraRepository movimientoMoraRepository;
    private final CreditoRepository creditoRepository;
    private final PagoRepository pagoRepository;
    private final InteresMoratorioService interesMoratorioService;

    public ReciboMoraService(MovimientoMoraRepository movimientoMoraRepository,
                             CreditoRepository creditoRepository,
                             PagoRepository pagoRepository,
                             InteresMoratorioService interesMoratorioService) {
        this.movimientoMoraRepository = movimientoMoraRepository;
        this.creditoRepository = creditoRepository;
        this.pagoRepository = pagoRepository;
        this.interesMoratorioService = interesMoratorioService;
    }

    public record DatosReciboMora(String numero, LocalDate fecha, String cliente, String documento,
                                  Long creditoId, BigDecimal monto, BigDecimal saldoMora, String recibio) {}

    public record ReciboMoraGenerado(String numero, byte[] pdf) {}

    // ===================== Datos =====================

    /**
     * @throws IllegalArgumentException si el movimiento o su crédito no existen.
     * @throws IllegalStateException si el movimiento es una condonación.
     */
    DatosReciboMora calcularDatos(Long movimientoId) {
        MovimientoMora movimiento = movimientoMoraRepository.findById(movimientoId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Movimiento de mora no encontrado con id: " + movimientoId));

        if (movimiento.getTipo() != TipoMovimientoMora.COBRO) {
            throw new IllegalStateException("Una condonación no genera recibo.");
        }

        Credito credito = creditoRepository.findById(movimiento.getCreditoId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Crédito no encontrado con id: " + movimiento.getCreditoId()));

        List<Pago> pagos = pagoRepository.findByCreditoId(credito.getId());
        BigDecimal devengadoALaFecha = interesMoratorioService.calcular(credito, pagos, movimiento.getFecha()).total();

        BigDecimal aplicadoHastaEseCobro = movimientoMoraRepository.findByCreditoIdOrderByIdDesc(credito.getId()).stream()
                .filter(m -> m.getId() <= movimiento.getId())
                .map(MovimientoMora::getMonto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal saldoMora = devengadoALaFecha.subtract(aplicadoHastaEseCobro).max(BigDecimal.ZERO);

        return new DatosReciboMora(
                "M-" + String.format("%06d", movimiento.getId()),
                movimiento.getFecha(),
                credito.getCliente().getNombreCompleto(),
                credito.getCliente().getNumeroDocumento(),
                credito.getId(),
                movimiento.getMonto(),
                saldoMora,
                movimiento.getUsuario());
    }

    // ===================== PDF =====================

    public ReciboMoraGenerado generarRecibo(Long movimientoId) throws DocumentException {
        DatosReciboMora datos = calcularDatos(movimientoId);
        float alto = buscarAltoMinimo(datos);
        return new ReciboMoraGenerado(datos.numero(), renderizar(datos, alto).pdf());
    }

    private record Render(byte[] pdf, int paginas) {}

    /** Busca el alto más pequeño con el que el recibo cabe en UNA página (el ticket no lleva papel en blanco). */
    private float buscarAltoMinimo(DatosReciboMora datos) throws DocumentException {
        float bajo = ALTO_MINIMO;
        float alto = ALTO_MEDICION;

        if (renderizar(datos, bajo).paginas() == 1) {
            return bajo;
        }
        while (alto - bajo > 2f) {
            float medio = (alto + bajo) / 2f;
            if (renderizar(datos, medio).paginas() == 1) {
                alto = medio;
            } else {
                bajo = medio;
            }
        }
        return alto + 4f;
    }

    private Render renderizar(DatosReciboMora datos, float alto) throws DocumentException {
        Document document = new Document(new Rectangle(ANCHO, alto), MARGEN, MARGEN, MARGEN, MARGEN);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter writer = PdfWriter.getInstance(document, out);
        document.open();

        agregarContenido(document, datos);

        int paginas = writer.getPageNumber();
        document.close();
        return new Render(out.toByteArray(), paginas);
    }

    private void agregarContenido(Document document, DatosReciboMora datos) throws DocumentException {
        document.add(centrado("Microcréditos", FONT_EMPRESA));
        document.add(centrado("RECIBO DE MORA", FONT_SUBTITULO));
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
        cliente.addCell(celda("Concepto:", FONT_NEGRITA, Element.ALIGN_LEFT));
        cliente.addCell(celda("Interés moratorio", FONT_NORMAL, Element.ALIGN_LEFT));
        document.add(cliente);
        document.add(separador());

        PdfPTable totales = tabla(new float[]{1.4f, 1f});
        totales.addCell(celda("TOTAL RECIBIDO", FONT_TOTAL, Element.ALIGN_LEFT));
        totales.addCell(celda(moneda(datos.monto()), FONT_TOTAL, Element.ALIGN_RIGHT));
        totales.addCell(celda("Saldo de mora", FONT_NORMAL, Element.ALIGN_LEFT));
        totales.addCell(celda(moneda(datos.saldoMora()), FONT_NORMAL, Element.ALIGN_RIGHT));
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
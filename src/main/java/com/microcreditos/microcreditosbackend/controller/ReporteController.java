package com.microcreditos.microcreditosbackend.controller;

import org.openpdf.text.DocumentException;
import com.microcreditos.microcreditosbackend.service.ReciboPagoService;
import com.microcreditos.microcreditosbackend.dto.ReporteCarteraFiltros;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.service.ReporteService;
import com.microcreditos.microcreditosbackend.service.ReciboMoraService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/reportes")
public class ReporteController {

    @Autowired
    private ReporteService reporteService;

    @Autowired
    private ReciboPagoService reciboPagoService;

    @Autowired
    private ReciboMoraService reciboMoraService;

    @GetMapping("/cartera")
    public ResponseEntity<byte[]> reporteCartera(
            @RequestParam(required = false) List<Credito.EstadoCredito> estados,
            @RequestParam(required = false) Long clienteId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaDesde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaHasta) {
        try {
            ReporteCarteraFiltros filtros = new ReporteCarteraFiltros(estados, clienteId, fechaDesde, fechaHasta);
            byte[] pdf = reporteService.generarReporteCartera(filtros);
            String nombreArchivo = "cartera_" + LocalDate.now() + ".pdf";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(ContentDisposition.attachment().filename(nombreArchivo).build());

            return ResponseEntity.ok().headers(headers).body(pdf);
        } catch (DocumentException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/cartera/excel")
    public ResponseEntity<byte[]> reporteCarteraExcel(
            @RequestParam(required = false) List<Credito.EstadoCredito> estados,
            @RequestParam(required = false) Long clienteId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaDesde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaHasta) {
        try {
            ReporteCarteraFiltros filtros = new ReporteCarteraFiltros(estados, clienteId, fechaDesde, fechaHasta);
            byte[] excel = reporteService.generarReporteCarteraExcel(filtros);
            String nombreArchivo = "cartera_" + LocalDate.now() + ".xlsx";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentDisposition(ContentDisposition.attachment().filename(nombreArchivo).build());

            return ResponseEntity.ok().headers(headers).body(excel);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/pagos/{creditoId}")
    public ResponseEntity<byte[]> reportePagos(@PathVariable Long creditoId) {
        try {
            byte[] pdf = reporteService.generarReportePagos(creditoId);
            String nombreArchivo = "pagos_credito_" + creditoId + "_" + LocalDate.now() + ".pdf";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(ContentDisposition.attachment().filename(nombreArchivo).build());

            return ResponseEntity.ok().headers(headers).body(pdf);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (DocumentException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/pagos/{creditoId}/excel")
    public ResponseEntity<byte[]> reportePagosExcel(@PathVariable Long creditoId) {
        try {
            byte[] excel = reporteService.generarReportePagosExcel(creditoId);
            String nombreArchivo = "pagos_credito_" + creditoId + "_" + LocalDate.now() + ".xlsx";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentDisposition(ContentDisposition.attachment().filename(nombreArchivo).build());

            return ResponseEntity.ok().headers(headers).body(excel);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/mora")
    public ResponseEntity<byte[]> reporteMora() {
        try {
            byte[] pdf = reporteService.generarReporteMora();
            String nombreArchivo = "mora_" + LocalDate.now() + ".pdf";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(ContentDisposition.attachment().filename(nombreArchivo).build());

            return ResponseEntity.ok().headers(headers).body(pdf);
        } catch (DocumentException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/mora/excel")
    public ResponseEntity<byte[]> reporteMoraExcel() {
        try {
            byte[] excel = reporteService.generarReporteMoraExcel();
            String nombreArchivo = "mora_" + LocalDate.now() + ".xlsx";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentDisposition(ContentDisposition.attachment().filename(nombreArchivo).build());

            return ResponseEntity.ok().headers(headers).body(excel);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/recibo/{pagoId}")
    public ResponseEntity<byte[]> reciboPago(@PathVariable Long pagoId) {
        try {
            ReciboPagoService.ReciboGenerado recibo = reciboPagoService.generarRecibo(pagoId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(
                    ContentDisposition.attachment().filename("recibo_" + recibo.numero() + ".pdf").build());

            return ResponseEntity.ok().headers(headers).body(recibo.pdf());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();   // abono anulado o fila de anulación
        } catch (DocumentException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/recibo-mora/{movimientoId}")
    public ResponseEntity<byte[]> reciboMora(@PathVariable Long movimientoId) {
        try {
            ReciboMoraService.ReciboMoraGenerado recibo = reciboMoraService.generarRecibo(movimientoId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(
                    ContentDisposition.attachment().filename("recibo_" + recibo.numero() + ".pdf").build());

            return ResponseEntity.ok().headers(headers).body(recibo.pdf());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();   // una condonación no genera recibo
        } catch (DocumentException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
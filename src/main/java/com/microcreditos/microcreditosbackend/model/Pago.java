package com.microcreditos.microcreditosbackend.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@Table(name = "pagos")
@Data
public class Pago {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "credito_id", nullable = false)
    @JsonBackReference
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Credito credito;

    @Column(nullable = false)
    private Integer numeroCuota;

    // Positivo en un abono normal; negativo en una fila de anulación (contra-asiento).
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal montoPagado;

    @Column(nullable = false)
    private LocalDate fechaPago;

    @Column(nullable = false)
    private LocalDate fechaVencimiento;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoPago estado;

    // Identificador compartido por todas las filas que nacen de un mismo abono
    // (un abono con derrame crea varias filas). NULL en pagos anteriores a V2.
    @Column(name = "abono_ref", length = 36)
    private String abonoRef;

    // Solo en filas de anulación: id del pago que esta fila revierte.
    @Column(name = "pago_original_id")
    private Long pagoOriginalId;

    // Solo en filas de anulación: motivo capturado por el ADMIN.
    @Column(name = "motivo_anulacion", length = 300)
    private String motivoAnulacion;

    // Desde V4. Los pagos anteriores quedaron como EFECTIVO. Una fila de anulación hereda
    // el método y la referencia del pago que revierte.
    @Enumerated(EnumType.STRING)
    @Column(name = "metodo_pago", nullable = false, length = 20)
    private MetodoPago metodoPago = MetodoPago.EFECTIVO;

    // Folio, clave de rastreo o número de ficha. Obligatoria si el método no es EFECTIVO.
    @Column(name = "referencia_pago", length = 50)
    private String referenciaPago;

    public enum EstadoPago {
        PENDIENTE, PAGADO, ATRASADO, ANULACION
    }
}
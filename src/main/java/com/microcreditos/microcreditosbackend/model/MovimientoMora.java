package com.microcreditos.microcreditosbackend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Un cobro o una condonación de interés moratorio de un crédito. Solo se inserta, nunca se edita.
 * Sin relación JPA con Credito a propósito (igual que Auditoria): basta el id del crédito, y la
 * llave foránea ya la garantiza la base.
 */
@Entity
@Table(name = "movimientos_mora")
@Data
public class MovimientoMora {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "credito_id", nullable = false)
    private Long creditoId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TipoMovimientoMora tipo;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal monto;

    @Column(nullable = false)
    private LocalDate fecha;

    // Obligatorio en CONDONACION (lo exige también un CHECK en la base).
    @Column(length = 300)
    private String motivo;

    // Quien cobró o condonó.
    @Column(nullable = false, length = 100)
    private String usuario;

    @Column(name = "creado_en", nullable = false)
    private LocalDateTime creadoEn;
}
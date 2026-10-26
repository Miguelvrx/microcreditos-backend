package com.microcreditos.microcreditosbackend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "notificaciones")
@Data
public class Notificacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TipoNotificacion tipo;

    @Column(nullable = false, length = 500)
    private String mensaje;

    // Sin @ManyToOne a propósito (igual que Auditoria): la notificación
    // sobrevive aunque el crédito cambie o se elimine.
    private Long creditoId;

    // Solo aplica a CUOTA_PROXIMA_A_VENCER; queda null en CREDITO_EN_MORA.
    // Se usa para no repetir el mismo aviso noche tras noche mientras la
    // cuota siga dentro de la ventana de anticipación.
    private Integer numeroCuota;

    @Column(nullable = false)
    private LocalDateTime fecha;

    @Column(nullable = false)
    private boolean leida = false;
}
package com.microcreditos.microcreditosbackend.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@Table(name = "creditos")
@Data
public class Credito {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "cliente_id", nullable = false)
    @JsonBackReference
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Cliente cliente;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal montoSolicitado;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal tasaInteresMensual;

    @Column(nullable = false)
    private Integer plazoMeses;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal cuotaMensual;

    @Column(nullable = false)
    private LocalDate fechaInicio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoCredito estado = EstadoCredito.PENDIENTE;

    @OneToMany(mappedBy = "credito", cascade = CascadeType.ALL)
    @JsonManagedReference
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Pago> pagos;

    // Desde V4. Opcional: un crédito puede no tener cobrador. NUNCA se serializa la entidad
    // Usuario completa (traería el hash de la contraseña): el JSON usa getCobradorAsignado().
    @ManyToOne
    @JoinColumn(name = "cobrador_id")
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Usuario cobrador;

    public record CobradorResumen(Long id, String nombreUsuario) {}

    /** Lo único del cobrador que sale en el JSON del crédito: id y nombre de usuario. */
    @JsonProperty("cobradorAsignado")
    public CobradorResumen getCobradorAsignado() {
        return cobrador == null ? null : new CobradorResumen(cobrador.getId(), cobrador.getNombreUsuario());
    }

    public enum EstadoCredito {
        PENDIENTE, APROBADO, RECHAZADO, PAGADO, EN_MORA
    }
}
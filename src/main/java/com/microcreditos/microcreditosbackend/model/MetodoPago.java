package com.microcreditos.microcreditosbackend.model;

/**
 * Cómo se recibió un abono. Transferencia y depósito exigen una referencia
 * (folio, clave de rastreo o número de ficha) para poder conciliar con el banco.
 */
public enum MetodoPago {
    EFECTIVO("Efectivo", false),
    TRANSFERENCIA("Transferencia", true),
    DEPOSITO("Depósito", true);

    private final String etiqueta;
    private final boolean exigeReferencia;

    MetodoPago(String etiqueta, boolean exigeReferencia) {
        this.etiqueta = etiqueta;
        this.exigeReferencia = exigeReferencia;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public boolean exigeReferencia() {
        return exigeReferencia;
    }
}
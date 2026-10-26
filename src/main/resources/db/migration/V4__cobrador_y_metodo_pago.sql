-- V4: método de pago de cada abono y cobrador asignado a cada crédito.
--
-- Método de pago: EFECTIVO, TRANSFERENCIA o DEPOSITO. Los pagos que ya existen quedan como EFECTIVO.
-- Referencia (folio, clave de rastreo o número de ficha): obligatoria para TRANSFERENCIA y DEPOSITO,
-- opcional para EFECTIVO. Las filas de anulación heredan método y referencia del pago que revierten,
-- por eso la regla también les aplica.
--
-- Cobrador: columna opcional en creditos (los créditos actuales quedan sin cobrador).

ALTER TABLE pagos
    ADD COLUMN metodo_pago     varchar(20) NOT NULL DEFAULT 'EFECTIVO',
    ADD COLUMN referencia_pago varchar(50);

ALTER TABLE pagos ADD CONSTRAINT pagos_metodo_pago_check
    CHECK (metodo_pago IN ('EFECTIVO', 'TRANSFERENCIA', 'DEPOSITO'));

ALTER TABLE pagos ADD CONSTRAINT pagos_referencia_check
    CHECK (metodo_pago = 'EFECTIVO'
           OR (referencia_pago IS NOT NULL AND length(btrim(referencia_pago)) > 0));

-- Para avisar cuando una referencia ya se usó en otro abono.
CREATE INDEX idx_pagos_referencia ON pagos (referencia_pago) WHERE referencia_pago IS NOT NULL;

ALTER TABLE creditos ADD COLUMN cobrador_id bigint REFERENCES usuarios (id);

CREATE INDEX idx_creditos_cobrador ON creditos (cobrador_id);

-- Una acción nueva de auditoría: se repite la lista COMPLETA (20 de V1/V2/V3 + 1 nueva).
ALTER TABLE auditoria DROP CONSTRAINT auditoria_accion_check;
ALTER TABLE auditoria ADD CONSTRAINT auditoria_accion_check CHECK (accion IN (
    'CREDITO_APROBADO', 'CREDITO_RECHAZADO', 'CREDITO_EN_MORA', 'CREDITO_PAGADO',
    'PAGO_REGISTRADO', 'USUARIO_CREADO', 'USUARIO_ACTIVADO', 'USUARIO_DESACTIVADO',
    'CREDITO_AL_DIA', 'CREDITO_ELIMINADO', 'CLIENTE_ELIMINADO', 'CREDITO_CREADO',
    'CLIENTE_CREADO', 'CLIENTE_EDITADO', 'LOGIN_EXITOSO', 'LOGIN_FALLIDO',
    'PAGO_ANULADO', 'CREDITO_REABIERTO',
    'MORA_COBRADA', 'MORA_CONDONADA',
    'COBRADOR_ASIGNADO'
));

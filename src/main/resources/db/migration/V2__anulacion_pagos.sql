-- V2: anulación de abonos mediante contra-asiento.
-- Una anulación inserta filas nuevas en pagos (estado ANULACION, monto negativo)
-- que apuntan al pago original; nunca se borra ni se edita un pago existente.

-- Une las filas de un mismo abono (un abono con derrame crea varias filas).
-- Los pagos anteriores a esta migración quedan en NULL y se tratan como abonos de una sola fila.
ALTER TABLE public.pagos ADD COLUMN abono_ref character varying(36);

-- En una fila de anulación: id del pago que revierte.
ALTER TABLE public.pagos ADD COLUMN pago_original_id bigint;

ALTER TABLE public.pagos ADD COLUMN motivo_anulacion character varying(300);

ALTER TABLE public.pagos
    ADD CONSTRAINT fk_pagos_pago_original FOREIGN KEY (pago_original_id) REFERENCES public.pagos(id);

-- Un pago solo puede ser anulado una vez (los NULL no cuentan para la unicidad).
ALTER TABLE public.pagos
    ADD CONSTRAINT uk_pagos_pago_original UNIQUE (pago_original_id);

CREATE INDEX idx_pagos_abono_ref ON public.pagos (abono_ref);

-- Nuevo estado ANULACION para pagos.
ALTER TABLE public.pagos DROP CONSTRAINT pagos_estado_check;
ALTER TABLE public.pagos ADD CONSTRAINT pagos_estado_check
    CHECK (estado IN ('PENDIENTE', 'PAGADO', 'ATRASADO', 'ANULACION'));

-- Nuevos valores de auditoría: PAGO_ANULADO y CREDITO_REABIERTO.
ALTER TABLE public.auditoria DROP CONSTRAINT auditoria_accion_check;
ALTER TABLE public.auditoria ADD CONSTRAINT auditoria_accion_check CHECK (accion IN (
    'CREDITO_APROBADO', 'CREDITO_RECHAZADO', 'CREDITO_EN_MORA', 'CREDITO_PAGADO',
    'PAGO_REGISTRADO', 'USUARIO_CREADO', 'USUARIO_ACTIVADO', 'USUARIO_DESACTIVADO',
    'CREDITO_AL_DIA', 'CREDITO_ELIMINADO', 'CLIENTE_ELIMINADO', 'CREDITO_CREADO',
    'CLIENTE_CREADO', 'CLIENTE_EDITADO', 'LOGIN_EXITOSO', 'LOGIN_FALLIDO',
    'PAGO_ANULADO', 'CREDITO_REABIERTO'));

ALTER TABLE solicitud
    ADD COLUMN control_vencido_alertado BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_solicitud_estado_fecha_control ON solicitud (estado, fecha_control);

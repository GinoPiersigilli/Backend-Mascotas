ALTER TABLE donacion
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD COLUMN request_hash    VARCHAR(64);

ALTER TABLE donacion
    ADD CONSTRAINT uk_donacion_donante_idempotency_key UNIQUE (donante_id, idempotency_key);

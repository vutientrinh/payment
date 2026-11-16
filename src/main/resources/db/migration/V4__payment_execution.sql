ALTER TABLE payments
    ADD COLUMN provider_reference VARCHAR(128),
    ADD COLUMN failure_reason     VARCHAR(255),
    ADD COLUMN version            BIGINT NOT NULL DEFAULT 0;
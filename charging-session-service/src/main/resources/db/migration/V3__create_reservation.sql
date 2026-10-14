-- Optimistic locking version for concurrent reservation protection
ALTER TABLE charging_point ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE charging_reservation (
    id                UUID         NOT NULL DEFAULT gen_random_uuid(),
    customer_id       VARCHAR(255) NOT NULL,
    charging_point_id UUID         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'CREATED',
    reserved_at       TIMESTAMPTZ  NOT NULL,
    valid_until       TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_charging_reservation              PRIMARY KEY (id),
    CONSTRAINT fk_charging_reservation_point        FOREIGN KEY (charging_point_id) REFERENCES charging_point(id),
    CONSTRAINT chk_charging_reservation_status      CHECK (status IN ('CREATED', 'CONFIRMED', 'USED', 'CANCELLED', 'EXPIRED'))
);

CREATE INDEX idx_charging_reservation_charging_point ON charging_reservation (charging_point_id);
CREATE INDEX idx_charging_reservation_customer       ON charging_reservation (customer_id);
CREATE INDEX idx_charging_reservation_status         ON charging_reservation (status);

-- Prevents two active reservations on the same charging point at the database level
CREATE UNIQUE INDEX idx_unique_active_reservation_per_point
    ON charging_reservation (charging_point_id)
    WHERE status IN ('CREATED', 'CONFIRMED');

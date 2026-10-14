CREATE TABLE charging_session (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    customer_id         VARCHAR(255) NOT NULL,
    charging_point_id   UUID         NOT NULL,
    reservation_id      UUID         NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'STARTED',
    started_at          TIMESTAMPTZ  NOT NULL,
    stopped_at          TIMESTAMPTZ,
    energy_consumed_kwh NUMERIC(10, 4),
    price_per_kwh       NUMERIC(8, 4) NOT NULL,
    total_cost          NUMERIC(10, 2),
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_charging_session            PRIMARY KEY (id),
    CONSTRAINT fk_charging_session_point      FOREIGN KEY (charging_point_id) REFERENCES charging_point(id),
    CONSTRAINT fk_charging_session_reservation FOREIGN KEY (reservation_id)   REFERENCES charging_reservation(id),
    CONSTRAINT chk_charging_session_status    CHECK (status IN ('STARTED','CHARGING','COMPLETED','FAILED'))
);

CREATE INDEX idx_charging_session_customer   ON charging_session (customer_id);
CREATE INDEX idx_charging_session_point      ON charging_session (charging_point_id);
CREATE INDEX idx_charging_session_status     ON charging_session (status);

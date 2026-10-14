CREATE TABLE outbox_event (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id   UUID         NOT NULL,
    aggregate_type VARCHAR(20)  NOT NULL,
    event_type     VARCHAR(30)  NOT NULL,
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ,
    status         VARCHAR(15)  NOT NULL DEFAULT 'NEW',
    CONSTRAINT pk_outbox_event  PRIMARY KEY (id),
    CONSTRAINT chk_outbox_status CHECK (status IN ('NEW', 'PUBLISHED'))
);

-- Partial index — only unprocessed rows; poller query uses this index directly.
CREATE INDEX idx_outbox_event_new ON outbox_event (created_at) WHERE status = 'NEW';

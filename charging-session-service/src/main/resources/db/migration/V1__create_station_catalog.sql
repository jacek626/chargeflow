CREATE TABLE charging_station (
    id        UUID         NOT NULL DEFAULT gen_random_uuid(),
    name      VARCHAR(255) NOT NULL,
    address   VARCHAR(255) NOT NULL,
    city      VARCHAR(100) NOT NULL,
    latitude  NUMERIC(9,6) NOT NULL,
    longitude NUMERIC(9,6) NOT NULL,
    active    BOOLEAN      NOT NULL DEFAULT true,
    CONSTRAINT pk_charging_station PRIMARY KEY (id)
);

CREATE TABLE charging_point (
    id             UUID          NOT NULL DEFAULT gen_random_uuid(),
    station_id     UUID          NOT NULL,
    code           VARCHAR(50)   NOT NULL,
    connector_type VARCHAR(20)   NOT NULL,
    max_power_kw   NUMERIC(8,2)  NOT NULL,
    price_per_kwh  NUMERIC(8,4)  NOT NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'AVAILABLE',
    active         BOOLEAN       NOT NULL DEFAULT true,
    CONSTRAINT pk_charging_point             PRIMARY KEY (id),
    CONSTRAINT fk_charging_point_station     FOREIGN KEY (station_id) REFERENCES charging_station(id),
    CONSTRAINT uq_charging_point_code        UNIQUE (code),
    CONSTRAINT chk_connector_type            CHECK (connector_type IN ('CCS', 'TYPE_2')),
    CONSTRAINT chk_charging_point_status     CHECK (status IN ('AVAILABLE', 'RESERVED', 'CHARGING', 'OUT_OF_SERVICE'))
);

CREATE INDEX idx_charging_station_active    ON charging_station (active)    WHERE active = true;
CREATE INDEX idx_charging_point_station_id  ON charging_point   (station_id);
CREATE INDEX idx_charging_point_status      ON charging_point   (status);

-- The inverters and the battery once a minute (HAS-328), a row only when a value changed. inverter_id is the id of
-- the inverter in the device registry of database-service (no foreign key - another database). Powers in W, signed:
-- negative where the battery discharges. Energies in kWh, voltages in V, currents in A, temperatures in °C, the state
-- of charge in %. run_mode is the word the API answers (normal, eps, ...), NULL for a code the service does not know.
-- A value the inverter does not give is NULL. pv_power is the power of both strings of panels together, pv1_power and
-- pv2_power each string. measured_at is the house time of the reading. Kept until the retention of the history task (HAS-331).
CREATE TABLE inverter_reading
(
    id          BIGSERIAL PRIMARY KEY,
    inverter_id BIGINT    NOT NULL,
    measured_at TIMESTAMP NOT NULL,
    pv_power    DOUBLE PRECISION,
    pv1_power   DOUBLE PRECISION,
    pv2_power   DOUBLE PRECISION,
    ac_power    DOUBLE PRECISION,
    run_mode    VARCHAR(20),
    temperature DOUBLE PRECISION,
    daily_yield DOUBLE PRECISION
);

CREATE INDEX inverter_reading_inverter_measured_at ON inverter_reading (inverter_id, measured_at);

CREATE TABLE battery_reading
(
    id          BIGSERIAL PRIMARY KEY,
    measured_at TIMESTAMP NOT NULL,
    soc         DOUBLE PRECISION,
    power       DOUBLE PRECISION,
    voltage     DOUBLE PRECISION,
    current     DOUBLE PRECISION,
    temperature DOUBLE PRECISION
);

CREATE INDEX battery_reading_measured_at ON battery_reading (measured_at);

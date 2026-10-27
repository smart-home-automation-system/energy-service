-- The grid meter once a minute (HAS-327), a row only when a value changed. Powers in W, signed: negative where
-- the house returns energy to the grid. Energies in kWh, from the counters of the meter (taken and returned since
-- it was set up). Voltages in V. A value the meter did not send is NULL. measured_at is the house time of the
-- readings. Kept until the retention of the history task (HAS-331).
CREATE TABLE grid_reading
(
    id            BIGSERIAL PRIMARY KEY,
    measured_at   TIMESTAMP NOT NULL,
    a_power       DOUBLE PRECISION,
    b_power       DOUBLE PRECISION,
    c_power       DOUBLE PRECISION,
    total_power   DOUBLE PRECISION,
    import_energy DOUBLE PRECISION,
    export_energy DOUBLE PRECISION,
    a_voltage     DOUBLE PRECISION,
    b_voltage     DOUBLE PRECISION,
    c_voltage     DOUBLE PRECISION
);

CREATE INDEX grid_reading_measured_at ON grid_reading (measured_at);

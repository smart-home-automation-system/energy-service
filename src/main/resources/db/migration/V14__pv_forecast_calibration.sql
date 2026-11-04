-- The calibration of the irradiance forecast on the closed days (HAS-372).
-- pv_forecast_day: one row a closed day and an inverter whose day had both a production (solax_yield / sofar_yield of
-- energy_day) and a forecast for every hour of the day (pv_forecast). forecast_kwh - what the forecast of the day gave
-- at a performance ratio of 1.0 (the peak power of the plane x the irradiance / 1000, summed over the hours), the raw
-- potential; actual_kwh - what the inverter produced. Written once, never changed; kept forever (a few kB a year).
CREATE TABLE pv_forecast_day
(
    day           DATE             NOT NULL,
    inverter      VARCHAR(16)      NOT NULL CHECK (inverter IN ('solax', 'sofar')),
    forecast_kwh  DOUBLE PRECISION NOT NULL CHECK (forecast_kwh >= 0),
    actual_kwh    DOUBLE PRECISION NOT NULL CHECK (actual_kwh >= 0),
    calculated_at TIMESTAMP        NOT NULL,
    PRIMARY KEY (day, inverter)
);

-- pv_forecast_calibration: the performance ratio in use for each inverter - the sum of actual_kwh over the sum of
-- forecast_kwh of the days of the calibration window, clamped 0.3 - 1.0; days - how many days it was computed on, 0 while
-- the initial ratio is used (fewer days than the minimum). calibrated_at - when the ratio or its days last changed.
CREATE TABLE pv_forecast_calibration
(
    inverter      VARCHAR(16)      PRIMARY KEY CHECK (inverter IN ('solax', 'sofar')),
    ratio         DOUBLE PRECISION NOT NULL CHECK (ratio > 0 AND ratio <= 1),
    days          INTEGER          NOT NULL CHECK (days >= 0),
    calibrated_at TIMESTAMP        NOT NULL
);

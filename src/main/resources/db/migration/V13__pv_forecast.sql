-- The irradiance forecast of the two planes of panels (HAS-371): one row an hour (house time, the start of the hour the
-- values cover) and an inverter. irradiance - the mean W/m2 on the plane of its panels (global_tilted_irradiance of
-- Open-Meteo), horizontal_irradiance on the ground (shortwave_radiation), cloud_cover %, temperature °C at 2 m, snowfall
-- cm; null what Open-Meteo did not give. fetched_at - the fetch that wrote the row: the latest forecast wins until the hour
-- starts, then the row is kept as it is for the calibration (HAS-372). Rows older than 60 days are deleted every night
CREATE TABLE pv_forecast
(
    hour                  TIMESTAMP        NOT NULL,
    inverter              VARCHAR(16)      NOT NULL CHECK (inverter IN ('solax', 'sofar')),
    irradiance            DOUBLE PRECISION CHECK (irradiance >= 0),
    horizontal_irradiance DOUBLE PRECISION CHECK (horizontal_irradiance >= 0),
    cloud_cover           DOUBLE PRECISION CHECK (cloud_cover BETWEEN 0 AND 100),
    temperature           DOUBLE PRECISION,
    snowfall              DOUBLE PRECISION CHECK (snowfall >= 0),
    fetched_at            TIMESTAMP        NOT NULL,
    PRIMARY KEY (hour, inverter)
);

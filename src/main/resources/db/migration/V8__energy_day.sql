-- The daily totals of the house (HAS-331).
-- battery_reading takes the energy the SOLAX counts for the battery today (kWh, reset at midnight by the inverter), so a
-- day can be closed from what was stored: the last values of the day are its totals.
ALTER TABLE battery_reading
    ADD COLUMN charged_today DOUBLE PRECISION;

ALTER TABLE battery_reading
    ADD COLUMN discharged_today DOUBLE PRECISION;

-- One row a day of the house, written once the day is over (shortly after midnight, house time), never changed after.
-- Energies in kWh: solax_yield and sofar_yield - the production of each inverter that day (its own daily counter),
-- production - their sum; imported, exported - the counters of the grid meter at the end of the day minus at its start;
-- charged, discharged - the battery of the SOLAX; house_load = production + imported - exported - charged + discharged.
-- self_consumption - the share of the production used in the house, (production - exported) / production; grid_share -
-- the share of the house load taken from the grid, imported / house_load; both 0..1, NULL when the divisor is 0.
-- cloud_yield, cloud_imported, cloud_exported - the same day as the SolaX cloud counts it for its plant (the SOLAX and its
-- meter), the cross-check; NULL when the cloud was not read late that day. A value the service could not establish is
-- NULL - never 0. Kept forever (about 400 bytes a day).
CREATE TABLE energy_day
(
    day              DATE PRIMARY KEY,
    solax_yield      DOUBLE PRECISION,
    sofar_yield      DOUBLE PRECISION,
    production       DOUBLE PRECISION,
    imported         DOUBLE PRECISION,
    exported         DOUBLE PRECISION,
    charged          DOUBLE PRECISION,
    discharged       DOUBLE PRECISION,
    house_load       DOUBLE PRECISION,
    self_consumption DOUBLE PRECISION,
    grid_share       DOUBLE PRECISION,
    cloud_yield      DOUBLE PRECISION,
    cloud_imported   DOUBLE PRECISION,
    cloud_exported   DOUBLE PRECISION,
    closed_at        TIMESTAMP NOT NULL
);

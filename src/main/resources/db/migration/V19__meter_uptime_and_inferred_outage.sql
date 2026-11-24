-- An outage of the grid seen after the fact (HAS-505): the grid meter (Shelly Pro 3EM) loses its power with the grid,
-- so a start of the meter after it was last seen is an outage the live watch of HAS-334 could not see - the house and
-- the cluster down with it.
-- grid_outage.inferred - the row was not seen live by the SOLAX but after the fact from the uptime of the meter: its
-- started_at / ended_at are bounds (the meter last seen, the meter started again), no state of charge.
-- meter_uptime - one row: the house time the meter last answered its status (seen_at) and the house time it started
-- (booted_at = seen_at - its uptime), both NULL until the first answer; written by every pass whose read answered
ALTER TABLE grid_outage
    ADD COLUMN inferred BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE meter_uptime
(
    id        INTEGER PRIMARY KEY CHECK (id = 1),
    seen_at   TIMESTAMP,
    booted_at TIMESTAMP
);

INSERT INTO meter_uptime (id) VALUES (1);

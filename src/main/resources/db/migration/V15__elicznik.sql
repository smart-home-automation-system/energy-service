-- The readings of the meter of the operator from the eLicznik portal of Tauron Dystrybucja (HAS-340) - the settlement
-- truth of the net-metering, beside the house's own 3EM.
-- One row an hour of a day the portal served whole, kWh: imported - taken from the grid, exported - given to it.
-- hour - the hour of the portal, 1-24, the hour ENDING at it (1 is 00:00 - 01:00, house time); repeated - the first of the
-- two 02:00 - 03:00 of the night the clocks go back (the portal's "T"), so that night has 25 rows and the night they go
-- forward 23 (no hour 3). A day is stored only once every hour of it is known, never changed after. Kept forever (about
-- 24 rows a day).
CREATE TABLE elicznik_hour
(
    day        DATE                        NOT NULL,
    hour       SMALLINT                    NOT NULL CHECK (hour BETWEEN 1 AND 24),
    repeated   BOOLEAN                     NOT NULL DEFAULT FALSE,
    imported   NUMERIC(9, 3)               NOT NULL CHECK (imported >= 0),
    exported   NUMERIC(9, 3)               NOT NULL CHECK (exported >= 0),
    fetched_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    PRIMARY KEY (day, hour, repeated),
    CHECK (NOT repeated OR hour = 3)
);

-- The registers of the meter at the end of a day (23:59:59 of the portal), whole kWh: imported - the register of the
-- energy taken (1.8.0), exported - the register of the energy given (2.8.0). One row a day, never changed after.
CREATE TABLE elicznik_meter
(
    day        DATE PRIMARY KEY,
    imported   NUMERIC(12, 3)              NOT NULL CHECK (imported >= 0),
    exported   NUMERIC(12, 3)              NOT NULL CHECK (exported >= 0),
    fetched_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

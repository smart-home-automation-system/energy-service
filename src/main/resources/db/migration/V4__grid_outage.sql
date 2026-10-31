-- The outages of the grid as the SOLAX saw them (HAS-334): a row opened when the grid is lost, closed when it is back.
-- started_at / ended_at are the house times of the first of the two reads that confirmed the loss / the return;
-- ended_at is NULL while the outage lasts - at most one such row (the partial unique index), the one a restart takes
-- up again. The state of charge of the battery in % at the start, at the end and the lowest seen in between.
-- last_reminded_at - the last notification of the outage (the alert or the hourly reminder), low_soc_alerted_at - the
-- alert of a battery below energy.outage.low-soc, at most one an outage; both kept here so that a restart neither
-- repeats nor forgets them
CREATE TABLE grid_outage
(
    id                 BIGSERIAL PRIMARY KEY,
    started_at         TIMESTAMP        NOT NULL,
    ended_at           TIMESTAMP,
    soc_at_start       DOUBLE PRECISION,
    soc_at_end         DOUBLE PRECISION,
    lowest_soc         DOUBLE PRECISION,
    last_reminded_at   TIMESTAMP        NOT NULL,
    low_soc_alerted_at TIMESTAMP
);

CREATE INDEX grid_outage_started_at ON grid_outage (started_at);

CREATE UNIQUE INDEX grid_outage_one_open ON grid_outage ((ended_at IS NULL)) WHERE ended_at IS NULL;

-- Load shedding during an outage of the grid (HAS-523, epic HAS-520). energy_automation.load_shedding - the switch of the
-- energy page, on by default (owner, 2026-11-25). load_shedding - one episode: active since started_at while ended_at is
-- null, ended by the grid back for the stable time or by the switch turned off; at most one open episode
ALTER TABLE energy_automation
    ADD COLUMN load_shedding BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE load_shedding
(
    id         BIGSERIAL PRIMARY KEY,
    started_at TIMESTAMP NOT NULL,
    ended_at   TIMESTAMP,
    ended_by   VARCHAR(16) CHECK (ended_by IN ('grid', 'switch')),
    CHECK ((ended_at IS NULL) = (ended_by IS NULL))
);

CREATE INDEX load_shedding_started_at ON load_shedding (started_at);

CREATE UNIQUE INDEX load_shedding_one_open ON load_shedding ((ended_at IS NULL)) WHERE ended_at IS NULL;

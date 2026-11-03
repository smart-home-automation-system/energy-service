-- The automatic charge of the battery before a planned outage (HAS-338). energy_automation - one row, the settings of the
-- panel: the switch (off until the owner turns it on), the target SOC, the charge power the start is computed with and the
-- margin before the outage. planned_outage.charge_run_id - the run the automation started for an outage, so that a window
-- is never started twice for it; battery_charge_run.planned_outage_id - the outage an automatic run was started for, so
-- that its cancel skips that outage
CREATE TABLE energy_automation
(
    id                   INTEGER PRIMARY KEY CHECK (id = 1),
    charge_before_outage BOOLEAN          NOT NULL DEFAULT FALSE,
    target_soc           INTEGER          NOT NULL DEFAULT 100 CHECK (target_soc BETWEEN 10 AND 100),
    charge_power_kw      DOUBLE PRECISION NOT NULL DEFAULT 5.0 CHECK (charge_power_kw BETWEEN 1 AND 8),
    margin_minutes       INTEGER          NOT NULL DEFAULT 60 CHECK (margin_minutes BETWEEN 0 AND 720),
    updated_at           TIMESTAMP
);

INSERT INTO energy_automation (id) VALUES (1);

ALTER TABLE planned_outage
    ADD COLUMN charge_run_id BIGINT;

ALTER TABLE battery_charge_run
    ADD COLUMN planned_outage_id BIGINT REFERENCES planned_outage (id);

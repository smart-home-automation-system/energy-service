-- The charges of the SOLAX battery from the grid (HAS-336): one row a run, house times. source - dashboard or
-- automation (HAS-338); the target SOC and the window of the run; request_id - the command of the SolaX cloud,
-- delivery_status its last status (1 offline, 2 issuance failed, 3 issued, 4 executing, 5 failed, 6 timeout) and
-- delivered_at when the inverter took it; the SOC at the start and at the end. ended_at / ended_by (target, window,
-- cancel, failed) and failure (the error code of a failed run) once it is over; restore_request_id / restored_at - the
-- command that set the baseline of Self Use back (restored_at also when nothing had to be set back - a command the
-- inverter certainly did not take). At most one run without its end (the partial unique index)
CREATE TABLE battery_charge_run
(
    id                 BIGSERIAL PRIMARY KEY,
    requested_at       TIMESTAMP   NOT NULL,
    source             VARCHAR(16) NOT NULL CHECK (source IN ('dashboard', 'automation')),
    target_soc         INTEGER     NOT NULL CHECK (target_soc BETWEEN 10 AND 100),
    window_start       TIMESTAMP   NOT NULL,
    window_end         TIMESTAMP   NOT NULL,
    request_id         VARCHAR(64),
    delivery_status    INTEGER CHECK (delivery_status BETWEEN 1 AND 6),
    delivered_at       TIMESTAMP,
    soc_at_start       DOUBLE PRECISION,
    soc_at_end         DOUBLE PRECISION,
    ended_at           TIMESTAMP,
    ended_by           VARCHAR(16) CHECK (ended_by IN ('target', 'window', 'cancel', 'failed')),
    failure            VARCHAR(32),
    restore_request_id VARCHAR(64),
    restored_at        TIMESTAMP,
    CONSTRAINT battery_charge_run_ended CHECK ((ended_at IS NULL) = (ended_by IS NULL)),
    CONSTRAINT battery_charge_run_window CHECK (window_end > window_start)
);

CREATE UNIQUE INDEX battery_charge_run_one_open ON battery_charge_run ((ended_at IS NULL)) WHERE ended_at IS NULL;

CREATE INDEX battery_charge_run_requested_at ON battery_charge_run (requested_at);

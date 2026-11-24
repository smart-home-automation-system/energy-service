-- The battery reserve (HAS-494): a part of the SOLAX battery the house never uses while the grid is present, held by the
-- Self Use floor of the inverter. energy_reserve - one row: the switch (off until the owner turns it on), the level, the
-- end of a reserve set until a time, and what became of the last write of the standing Self Use - none (never written
-- by the service, the inverter runs what the owner set), pending (to be written), sent (the cloud took the command),
-- taken (the inverter is executing it), failed (refused or not carried out - tried again every 10 minutes)
CREATE TABLE energy_reserve
(
    id                 INTEGER PRIMARY KEY CHECK (id = 1),
    enabled            BOOLEAN     NOT NULL DEFAULT FALSE,
    level              INTEGER     NOT NULL DEFAULT 80 CHECK (level BETWEEN 10 AND 100 AND level % 5 = 0),
    until_at           TIMESTAMP,
    updated_at         TIMESTAMP,
    write_outcome      VARCHAR(16) NOT NULL DEFAULT 'none'
        CHECK (write_outcome IN ('none', 'pending', 'sent', 'taken', 'failed')),
    write_request_id   VARCHAR(64),
    write_attempted_at TIMESTAMP
);

INSERT INTO energy_reserve (id) VALUES (1);

-- The outages Tauron Dystrybucja announces for the house (HAS-337): one row an outage - its id at Tauron and its start
-- (the days of one work share the id), house times. kind planned / unplanned (a failure); scope street (the street of
-- the house named) / town (the town alone). notice - what is still to be told (new, changed, moved, cancelled), NULL
-- when nothing is; moved_from - the start before a move. notified_at - the last notice published; the reminders of a
-- planned outage of the street (the day before, before the start). closed_at / closed_as once it is no longer
-- announced: ended, cancelled (a planned one still ahead) or moved. At most one open row an id and a start (the partial
-- unique index). skip_automation - no charge of the battery for it (HAS-338)
CREATE TABLE planned_outage
(
    id                       BIGSERIAL PRIMARY KEY,
    outage_id                VARCHAR(64) NOT NULL,
    kind                     VARCHAR(16) NOT NULL CHECK (kind IN ('planned', 'unplanned')),
    scope                    VARCHAR(16) NOT NULL CHECK (scope IN ('street', 'town')),
    starts_at                TIMESTAMP   NOT NULL,
    ends_at                  TIMESTAMP   NOT NULL,
    description              TEXT        NOT NULL,
    first_seen_at            TIMESTAMP   NOT NULL,
    last_seen_at             TIMESTAMP   NOT NULL,
    notice                   VARCHAR(16) CHECK (notice IN ('new', 'changed', 'moved', 'cancelled')),
    moved_from               TIMESTAMP,
    notified_at              TIMESTAMP,
    day_before_reminded_at   TIMESTAMP,
    before_start_reminded_at TIMESTAMP,
    closed_at                TIMESTAMP,
    closed_as                VARCHAR(16) CHECK (closed_as IN ('ended', 'cancelled', 'moved')),
    skip_automation          BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT planned_outage_closed CHECK ((closed_at IS NULL) = (closed_as IS NULL)),
    CONSTRAINT planned_outage_ends_after_start CHECK (ends_at > starts_at)
);

CREATE UNIQUE INDEX planned_outage_one_open ON planned_outage (outage_id, starts_at) WHERE closed_at IS NULL;

CREATE INDEX planned_outage_starts_at ON planned_outage (starts_at);

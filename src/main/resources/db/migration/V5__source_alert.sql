-- The alerts of the sources of the house (HAS-332), one row for each episode the household is told of:
-- a source silent (kind silent: the SOLAX dongle not answering, the grid meter silent on MQTT and RPC) or a
-- phase of the grid meter above its warning current (kind overload, source phase a / b / c).
-- opened_at - since when: the last time a silent source was heard, the first reading of an overload above the
-- threshold. last_told_at - when the alert (or its last reminder) was published; NULL while the alert has not
-- got through yet, so that its return announces it. closed_at - the return, NULL while it lasts: at most one
-- open row a source and kind (the partial unique index) - what a restart takes up again
CREATE TABLE source_alert
(
    id           BIGSERIAL PRIMARY KEY,
    source       VARCHAR(20) NOT NULL,
    kind         VARCHAR(20) NOT NULL,
    opened_at    TIMESTAMP   NOT NULL,
    last_told_at TIMESTAMP,
    closed_at    TIMESTAMP
);

ALTER TABLE source_alert
    ADD CONSTRAINT source_alert_source_check CHECK (source IN ('solax', 'meter', 'phase a', 'phase b', 'phase c'));

ALTER TABLE source_alert
    ADD CONSTRAINT source_alert_kind_check CHECK (
        (kind = 'silent' AND source IN ('solax', 'meter'))
        OR (kind = 'overload' AND source IN ('phase a', 'phase b', 'phase c')));

CREATE INDEX source_alert_opened_at ON source_alert (opened_at);

CREATE UNIQUE INDEX source_alert_one_open ON source_alert (source, kind) WHERE closed_at IS NULL;

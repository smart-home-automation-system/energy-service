-- The alarms of the SOLAX from the SolaX cloud (HAS-329), kept in source_alert with the episodes of HAS-332: source solax,
-- kind alarm, detail - what tells one alarm from another, "<error code>|<start time>|<name>" as the cloud answers them (the
-- alarm and its name are told in the notifications). An alarm is one occurrence: the same error again later is another
-- row. At most one open row a source, kind and detail - the index of V5 (one a source and kind) would allow one alarm only
ALTER TABLE source_alert
    ADD COLUMN detail VARCHAR(200);

ALTER TABLE source_alert
    DROP CONSTRAINT source_alert_kind_check;

ALTER TABLE source_alert
    ADD CONSTRAINT source_alert_kind_check CHECK (
        (kind = 'silent' AND source IN ('solax', 'meter') AND detail IS NULL)
        OR (kind = 'overload' AND source IN ('phase a', 'phase b', 'phase c') AND detail IS NULL)
        OR (kind = 'alarm' AND source = 'solax' AND detail IS NOT NULL));

DROP INDEX source_alert_one_open;

CREATE UNIQUE INDEX source_alert_one_open ON source_alert (source, kind, COALESCE(detail, '')) WHERE closed_at IS NULL;

-- The battery reserve the inverter does not hold (HAS-518), kept in source_alert with the episodes of HAS-332: source
-- solax, kind reserve, no detail - one open row (the unique index of V6 is a source, a kind and a detail, so it may be
-- open beside a silent SOLAX, its alarms and its production). The source check takes solax already; the kind check
-- takes the new kind
ALTER TABLE source_alert
    DROP CONSTRAINT source_alert_kind_check;

ALTER TABLE source_alert
    ADD CONSTRAINT source_alert_kind_check CHECK (
        (kind = 'silent' AND source IN ('solax', 'meter') AND detail IS NULL)
        OR (kind = 'overload' AND source IN ('phase a', 'phase b', 'phase c') AND detail IS NULL)
        OR (kind = 'alarm' AND source IN ('solax', 'sofar') AND detail IS NOT NULL)
        OR (kind = 'production' AND source IN ('solax', 'sofar') AND detail IS NULL)
        OR (kind = 'frequency' AND source = 'meter' AND detail IS NULL)
        OR (kind = 'reserve' AND source = 'solax' AND detail IS NULL));

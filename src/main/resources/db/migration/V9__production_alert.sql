-- The inverter that does not produce in the sun (HAS-333), kept in source_alert with the episodes of HAS-332: source solax
-- or sofar, kind production, no detail - one open row an inverter (the unique index of V6). Both checks take the kind
ALTER TABLE source_alert
    DROP CONSTRAINT source_alert_kind_check;

ALTER TABLE source_alert
    ADD CONSTRAINT source_alert_kind_check CHECK (
        (kind = 'silent' AND source IN ('solax', 'meter') AND detail IS NULL)
        OR (kind = 'overload' AND source IN ('phase a', 'phase b', 'phase c') AND detail IS NULL)
        OR (kind = 'alarm' AND source IN ('solax', 'sofar') AND detail IS NOT NULL)
        OR (kind = 'production' AND source IN ('solax', 'sofar') AND detail IS NULL));

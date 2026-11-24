-- The grid frequency off its band (HAS-503), kept in source_alert with the episodes of HAS-332: source meter (the grid
-- meter measures it), kind frequency, no detail - one open row (the unique index of V6 is a source and a kind, so a
-- silent meter and an off frequency may be open at once). The source check takes
-- meter already; the kind check takes the new kind
ALTER TABLE source_alert
    DROP CONSTRAINT source_alert_kind_check;

ALTER TABLE source_alert
    ADD CONSTRAINT source_alert_kind_check CHECK (
        (kind = 'silent' AND source IN ('solax', 'meter') AND detail IS NULL)
        OR (kind = 'overload' AND source IN ('phase a', 'phase b', 'phase c') AND detail IS NULL)
        OR (kind = 'alarm' AND source IN ('solax', 'sofar') AND detail IS NOT NULL)
        OR (kind = 'production' AND source IN ('solax', 'sofar') AND detail IS NULL)
        OR (kind = 'frequency' AND source = 'meter' AND detail IS NULL));

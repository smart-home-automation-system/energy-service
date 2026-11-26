-- The battery below its reserve (HAS-495), kept in source_alert beside the reserve the inverter does not hold (V20,
-- HAS-518): source battery, kind reserve, no detail - one open row (the unique index of V6 is a source, a kind and a
-- detail, so it may be open beside the episode of the floor, source solax). Both checks take the new source
ALTER TABLE source_alert
    DROP CONSTRAINT source_alert_source_check;

ALTER TABLE source_alert
    ADD CONSTRAINT source_alert_source_check CHECK (source IN ('solax', 'sofar', 'meter', 'phase a', 'phase b',
                                                               'phase c', 'battery'));

ALTER TABLE source_alert
    DROP CONSTRAINT source_alert_kind_check;

ALTER TABLE source_alert
    ADD CONSTRAINT source_alert_kind_check CHECK (
        (kind = 'silent' AND source IN ('solax', 'meter') AND detail IS NULL)
        OR (kind = 'overload' AND source IN ('phase a', 'phase b', 'phase c') AND detail IS NULL)
        OR (kind = 'alarm' AND source IN ('solax', 'sofar') AND detail IS NOT NULL)
        OR (kind = 'production' AND source IN ('solax', 'sofar') AND detail IS NULL)
        OR (kind = 'frequency' AND source = 'meter' AND detail IS NULL)
        OR (kind = 'reserve' AND source IN ('solax', 'battery') AND detail IS NULL));

-- The faults of the SOFAR from the Solarman cloud (HAS-330), kept in source_alert with the alarms of the SOLAX: source sofar,
-- kind alarm, detail - the words of the fault as the cloud answers them (Sta_1 - Sta_9). Both checks take the new source;
-- the SOFAR is watched for its faults only, never for silence
ALTER TABLE source_alert
    DROP CONSTRAINT source_alert_source_check;

ALTER TABLE source_alert
    ADD CONSTRAINT source_alert_source_check CHECK (source IN ('solax', 'sofar', 'meter', 'phase a', 'phase b', 'phase c'));

ALTER TABLE source_alert
    DROP CONSTRAINT source_alert_kind_check;

ALTER TABLE source_alert
    ADD CONSTRAINT source_alert_kind_check CHECK (
        (kind = 'silent' AND source IN ('solax', 'meter') AND detail IS NULL)
        OR (kind = 'overload' AND source IN ('phase a', 'phase b', 'phase c') AND detail IS NULL)
        OR (kind = 'alarm' AND source IN ('solax', 'sofar') AND detail IS NOT NULL));

-- The currents and the frequency of the grid meter (HAS-506), for their history. Currents in A per phase, the frequency
-- in Hz - one value, the phase furthest from 50 Hz, as the frequency alert judges it (HAS-503). NULL for a value the
-- meter did not send; the rows written before this migration stay NULL - their history starts with the deploy.
ALTER TABLE grid_reading
    ADD COLUMN a_current DOUBLE PRECISION,
    ADD COLUMN b_current DOUBLE PRECISION,
    ADD COLUMN c_current DOUBLE PRECISION,
    ADD COLUMN frequency DOUBLE PRECISION;

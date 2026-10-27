package cloud.cholewa.energy.meter;

import cloud.cholewa.shelly.model.EmDataStatus;
import cloud.cholewa.shelly.model.EmStatus;

import java.time.LocalDateTime;

//What the service holds of the grid meter, in memory: the last readings (em:0) and counters (emdata:0) with the
//house time they arrived at, and whether the meter is on the broker. Everything is null until it was heard of -
//after a start the meter is not known until the first message or the first read of the reconciliation.
//online - the "online" topic of the meter: false is its last will, true its return; a reading counts as true
public record GridMeterState(
    EmStatus readings,
    LocalDateTime readingsAt,
    EmDataStatus counters,
    LocalDateTime countersAt,
    Boolean online
) {

    static final GridMeterState UNKNOWN = new GridMeterState(null, null, null, null, null);

    GridMeterState withReadings(final EmStatus status, final LocalDateTime at) {
        return new GridMeterState(status, at, counters, countersAt, true);
    }

    GridMeterState withCounters(final EmDataStatus status, final LocalDateTime at) {
        return new GridMeterState(readings, readingsAt, status, at, true);
    }

    GridMeterState withOnline(final boolean value) {
        return new GridMeterState(readings, readingsAt, counters, countersAt, value);
    }

    //the newer of the two arrivals, or null when neither came yet
    public LocalDateTime lastReadingAt() {
        if (readingsAt == null) {
            return countersAt;
        }
        return countersAt == null || readingsAt.isAfter(countersAt) ? readingsAt : countersAt;
    }
}

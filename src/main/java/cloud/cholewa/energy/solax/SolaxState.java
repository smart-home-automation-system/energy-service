package cloud.cholewa.energy.solax;

import java.time.Duration;
import java.time.LocalDateTime;

//What the service holds of the SOLAX, in memory (HAS-328): the last reading with the house time it arrived at, the
//registry id of the inverter it is of, and whether the last read succeeded. Everything is null until the first read -
//after a start the inverter is not known. A failed read keeps the last reading (served as stale once it is old)
public record SolaxState(
    Long inverterId,
    SolaxReading reading,
    LocalDateTime readingAt,
    Boolean reachable
) {

    static final SolaxState UNKNOWN = new SolaxState(null, null, null, null);

    SolaxState withReading(final long id, final SolaxReading value, final LocalDateTime at) {
        return new SolaxState(id, value, at, true);
    }

    SolaxState unreachable() {
        return new SolaxState(inverterId, reading, readingAt, false);
    }

    //no reading, the last read failed, or the reading is older than the bound
    public boolean isStale(final LocalDateTime now, final Duration staleAfter) {
        return readingAt == null || Boolean.FALSE.equals(reachable) || readingAt.isBefore(now.minus(staleAfter));
    }
}

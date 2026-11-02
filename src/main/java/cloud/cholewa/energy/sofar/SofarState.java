package cloud.cholewa.energy.sofar;

import java.time.Duration;
import java.time.LocalDateTime;

//What the service holds of the SOFAR, in memory (HAS-330): the last reading, the registry id of the inverter it is of,
//the house time of the last read and whether it brought a fresh reading - the cloud answered and its logger collected
//the reading within staleAfter (a logger that is off leaves the cloud answering its last reading). Everything is null
//until the first read; a failed read keeps the last reading
public record SofarState(
    Long inverterId,
    SofarReading reading,
    LocalDateTime readAt,
    Boolean reachable
) {

    static final SofarState UNKNOWN = new SofarState(null, null, null, null);

    SofarState withReading(final long id, final SofarReading value, final LocalDateTime at, final boolean fresh) {
        return new SofarState(id, value, at, fresh);
    }

    SofarState unreachable(final LocalDateTime at) {
        return new SofarState(inverterId, reading, at, false);
    }

    //the house time the logger collected the last reading, null without one
    public LocalDateTime readingAt() {
        return reading == null ? null : reading.collectedAt();
    }

    //no reading, the last read failed, or the reading was collected longer ago than the bound
    public boolean isStale(final LocalDateTime now, final Duration staleAfter) {
        return reading == null || Boolean.FALSE.equals(reachable) || reading.collectedAt().isBefore(now.minus(staleAfter));
    }
}

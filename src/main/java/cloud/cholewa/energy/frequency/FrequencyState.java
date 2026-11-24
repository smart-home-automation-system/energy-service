package cloud.cholewa.energy.frequency;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

//The grid frequency as the last pass of FrequencyWatch saw it (HAS-503), in memory - for GET /status and the automatic
//charge before an outage. Both null until the first pass and while the grid meter is stale: not known, never read as
//inside the band
@Component
public class FrequencyState {

    //hz - the frequency of the grid meter furthest from 50 Hz; off - whether it is outside the band now (a single
    //reading, not the episode of the alert)
    public record Snapshot(Double hz, Boolean off) {

        static final Snapshot NOT_KNOWN = new Snapshot(null, null);
    }

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.NOT_KNOWN);

    public Snapshot current() {
        return snapshot.get();
    }

    //true only for a known frequency outside the band
    public boolean off() {
        return Boolean.TRUE.equals(snapshot.get().off());
    }

    void set(final Snapshot value) {
        snapshot.set(value);
    }
}

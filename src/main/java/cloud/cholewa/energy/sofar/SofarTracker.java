package cloud.cholewa.energy.sofar;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

//The state of the SOFAR, in memory (HAS-330): written by the reader, read by the API, the status, the snapshot and the
//fault watch - one AtomicReference, so a reader always sees a whole state. A change of the state of the inverter is an
//INFO with the words of the cloud
@Slf4j
@Component
public class SofarTracker {

    private final AtomicReference<SofarState> state = new AtomicReference<>(SofarState.UNKNOWN);

    public SofarState current() {
        return state.get();
    }

    void read(final long inverterId, final SofarReading reading, final LocalDateTime at, final boolean fresh) {
        final SofarState before = state.getAndUpdate(current -> current.withReading(inverterId, reading, at, fresh));
        final String stateBefore = before.reading() == null ? null : before.reading().state();
        if (!Objects.equals(stateBefore, reading.state())) {
            log.info("The state of the SOFAR is {}", Objects.requireNonNullElse(reading.state(), "not known"));
        }
    }

    void failed(final LocalDateTime at) {
        state.updateAndGet(current -> current.unreachable(at));
    }
}

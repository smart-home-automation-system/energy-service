package cloud.cholewa.energy.solax;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

//The state of the SOLAX, in memory (HAS-328): written by the reader, read by the API, the status and the snapshot of
//every minute - one AtomicReference, so a reader always sees a whole state. A change of the run mode is logged with its
//raw code: the words are the API's, the code is what a fault report needs
@Slf4j
@Component
@RequiredArgsConstructor
public class SolaxTracker {

    private final Clock clock;
    private final AtomicReference<SolaxState> state = new AtomicReference<>(SolaxState.UNKNOWN);

    public SolaxState current() {
        return state.get();
    }

    void read(final long inverterId, final SolaxReading reading) {
        final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        final SolaxState before = state.getAndUpdate(current -> current.withReading(inverterId, reading, now));
        final Integer modeBefore = before.reading() == null ? null : before.reading().runModeCode();
        if (!Objects.equals(modeBefore, reading.runModeCode())) {
            log.info("The run mode of the SOLAX is {} (code {})",
                reading.runMode() == null ? "not known" : reading.runMode().wireName(), reading.runModeCode());
        }
    }

    void failed() {
        state.updateAndGet(SolaxState::unreachable);
    }
}

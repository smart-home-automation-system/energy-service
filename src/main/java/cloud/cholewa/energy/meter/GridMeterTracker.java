package cloud.cholewa.energy.meter;

import cloud.cholewa.shelly.model.EmDataStatus;
import cloud.cholewa.shelly.model.EmStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

//The state of the grid meter, in memory (HAS-327). Written by the MQTT listener and by the reconciliation over
//RPC, read by the API and by the snapshot of every minute - one AtomicReference, so a reader always sees a whole
//state. A message and a read are the meter as it was at their arrival, so the later one wins: after a gap of the
//broker the RPC values replace the old MQTT state, and the next message replaces them again
@Slf4j
@Component
@RequiredArgsConstructor
public class GridMeterTracker {

    private final Clock clock;
    private final AtomicReference<GridMeterState> state = new AtomicReference<>(GridMeterState.UNKNOWN);

    public GridMeterState current() {
        return state.get();
    }

    void readings(final EmStatus status) {
        final LocalDateTime now = now();
        state.updateAndGet(current -> current.withReadings(status, now));
    }

    void counters(final EmDataStatus status) {
        final LocalDateTime now = now();
        state.updateAndGet(current -> current.withCounters(status, now));
    }

    //cut to what the column of grid_reading keeps, so the API and the database tell the same time
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    //the last will (false) and the return (true) of the meter on the broker; logged when it changes - it is
    //rare, and it is what tells a silent meter from a quiet broker
    void online(final boolean online) {
        final GridMeterState before = state.getAndUpdate(current -> current.withOnline(online));
        if (!Boolean.valueOf(online).equals(before.online())) {
            log.info("The grid meter is {} the broker", online ? "back on" : "off");
        }
    }
}

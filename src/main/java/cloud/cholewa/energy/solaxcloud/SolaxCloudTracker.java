package cloud.cholewa.energy.solaxcloud;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

//The state of the SolaX cloud, in memory (HAS-329): written by the reader, read by the API and the status - one
//AtomicReference, so a reader always sees a whole state
@Component
public class SolaxCloudTracker {

    private final AtomicReference<SolaxCloudState> state = new AtomicReference<>(SolaxCloudState.UNKNOWN);

    public SolaxCloudState current() {
        return state.get();
    }

    void read(final CloudInverter inverter, final CloudBattery battery, final LocalDateTime at) {
        state.updateAndGet(current -> current.withRead(inverter, battery, at));
    }

    void failed() {
        state.updateAndGet(SolaxCloudState::unreachable);
    }
}

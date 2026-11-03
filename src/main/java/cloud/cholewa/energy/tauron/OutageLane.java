package cloud.cholewa.energy.tauron;

import cloud.cholewa.energy.history.OneAtATime;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

//The one lane every write to planned_outage runs in (HAS-338): the pass of PlannedOutageWatch saves whole rows read a
//moment before, so the skip of the panel and the run the automation started must not land in between and be overwritten.
//Never call the lane of the charge (InverterCommands) from inside it - the charge may call this lane, not the other way
@Component
public class OutageLane {

    private final OneAtATime lane = new OneAtATime();

    public <T> Mono<T> serially(final Mono<T> work) {
        return lane.run(work);
    }
}

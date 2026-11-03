package cloud.cholewa.energy.charge;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

//Whether a charge may be open or being ended (HAS-336), so that the watch asks the database every ten seconds only then:
//raised at the start of the pod and by every start, lowered by the watch once the newest run is over and set back
@Component
public class ChargeWatchFlag {

    private final AtomicBoolean raised = new AtomicBoolean(true);

    void raise() {
        raised.set(true);
    }

    void lower() {
        raised.set(false);
    }

    boolean raised() {
        return raised.get();
    }
}

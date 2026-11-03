package cloud.cholewa.energy.automation;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//The planned start of the automatic charge (HAS-338), recomputed by the automation every minute and read by the API - the
//next planned outage of the street not skipped, and when its charge would start at the SOC now; none without such an
//outage, a known SOC or anything to charge. Whether the switch is on is not part of it: the panel shows the start either way
@Component
public class AutomationPlan {

    public record Planned(Long outageId, LocalDateTime outageStart, LocalDateTime plannedStart) {
    }

    private final AtomicReference<Planned> planned = new AtomicReference<>();

    public Optional<Planned> current() {
        return Optional.ofNullable(planned.get());
    }

    //the planned start for this outage, if it is the one planned
    public Optional<LocalDateTime> startFor(final Long outageId) {
        return current().filter(plan -> plan.outageId().equals(outageId)).map(Planned::plannedStart);
    }

    void set(final Planned plan) {
        planned.set(plan);
    }
}

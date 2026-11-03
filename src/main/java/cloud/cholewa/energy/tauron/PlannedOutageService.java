package cloud.cholewa.energy.tauron;

import cloud.cholewa.energy.api.model.PlannedOutageReply;
import cloud.cholewa.energy.automation.AutomationPlan;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.database.PlannedOutageEntity;
import cloud.cholewa.energy.database.PlannedOutageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

//The planned outages for the API (HAS-337): the ones still announced that have not ended, the soonest first. The computed
//start of the charge comes with the automation task (HAS-338)
@Service
@RequiredArgsConstructor
public class PlannedOutageService {

    private final PlannedOutageRepository repository;
    private final OutageLane lane;
    private final AutomationPlan automationPlan;
    private final Clock clock;

    public Mono<List<PlannedOutageReply>> planned() {
        return repository.findByClosedAtIsNullAndEndsAtAfterOrderByStartsAt(LocalDateTime.now(clock))
            .map(this::reply)
            .collectList();
    }

    //the automatic charge skipped or allowed again for an outage still announced (HAS-338) - NOT_FOUND_PLANNED_OUTAGE for
    //another; in the lane of planned_outage, so the watch of Tauron does not write the old value back
    public Mono<PlannedOutageReply> skip(final long id, final boolean skip) {
        return lane.serially(repository.setSkipAutomation(id, skip))
            .filter(changed -> changed > 0)
            .switchIfEmpty(Mono.error(() -> EnergyException.plannedOutageNotFound(id)))
            .then(repository.findById(id))
            .map(this::reply);
    }

    PlannedOutageReply reply(final PlannedOutageEntity row) {
        return new PlannedOutageReply(row.id(), row.startsAt(), row.endsAt(), row.kind(), row.scope(), row.description(),
            row.firstSeenAt(), row.notifiedAt(), row.skipAutomation(), automationPlan.startFor(row.id()).orElse(null));
    }
}

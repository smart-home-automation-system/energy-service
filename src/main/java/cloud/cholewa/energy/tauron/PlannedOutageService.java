package cloud.cholewa.energy.tauron;

import cloud.cholewa.energy.api.model.PlannedOutageReply;
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
    private final Clock clock;

    public Mono<List<PlannedOutageReply>> planned() {
        return repository.findByClosedAtIsNullAndEndsAtAfterOrderByStartsAt(LocalDateTime.now(clock))
            .map(PlannedOutageService::reply)
            .collectList();
    }

    static PlannedOutageReply reply(final PlannedOutageEntity row) {
        return new PlannedOutageReply(row.startsAt(), row.endsAt(), row.kind(), row.scope(), row.description(),
            row.firstSeenAt(), row.notifiedAt(), row.skipAutomation());
    }
}

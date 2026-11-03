package cloud.cholewa.energy.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;

public interface PlannedOutageRepository extends ReactiveCrudRepository<PlannedOutageEntity, Long> {

    //the outages still announced - at most one an id and a start (the partial unique index of V10)
    Flux<PlannedOutageEntity> findByClosedAtIsNull();

    //the outages a notice is due for - the announced ones and the closed ones whose cancellation is still to be told
    Flux<PlannedOutageEntity> findByClosedAtIsNullOrNoticeIsNotNull();

    //the coming ones and the one going on, the soonest first - GET /home/energy/outages/planned
    Flux<PlannedOutageEntity> findByClosedAtIsNullAndEndsAtAfterOrderByStartsAt(LocalDateTime now);
}

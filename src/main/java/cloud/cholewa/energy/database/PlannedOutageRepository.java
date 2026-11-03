package cloud.cholewa.energy.database;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface PlannedOutageRepository extends ReactiveCrudRepository<PlannedOutageEntity, Long> {

    //the outages still announced - at most one an id and a start (the partial unique index of V10)
    Flux<PlannedOutageEntity> findByClosedAtIsNull();

    //the outages a notice is due for - the announced ones and the closed ones whose cancellation is still to be told
    Flux<PlannedOutageEntity> findByClosedAtIsNullOrNoticeIsNotNull();

    //the coming ones and the one going on, the soonest first - GET /home/energy/outages/planned
    Flux<PlannedOutageEntity> findByClosedAtIsNullAndEndsAtAfterOrderByStartsAt(LocalDateTime now);

    //the next outage of this kind and scope starting after now, not skipped (HAS-338)
    Mono<PlannedOutageEntity> findFirstByClosedAtIsNullAndKindAndScopeAndSkipAutomationFalseAndStartsAtAfterOrderByStartsAt(
        String kind, String scope, LocalDateTime now);

    //the skip of the panel, on an outage still announced - the number of rows changed (HAS-338)
    @Modifying
    @Query("UPDATE planned_outage SET skip_automation = :skip WHERE id = :id AND closed_at IS NULL")
    Mono<Integer> setSkipAutomation(Long id, boolean skip);

    //the run the automation started for an outage (HAS-338)
    @Modifying
    @Query("UPDATE planned_outage SET charge_run_id = :run WHERE id = :id")
    Mono<Integer> setChargeRun(Long id, Long run);
}

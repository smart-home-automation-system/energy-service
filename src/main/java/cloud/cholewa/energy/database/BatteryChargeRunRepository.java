package cloud.cholewa.energy.database;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface BatteryChargeRunRepository extends ReactiveCrudRepository<BatteryChargeRunEntity, Long> {

    //the newest run - the open one, or the last ended one whose baseline may still be due
    Mono<BatteryChargeRunEntity> findFirstByOrderByRequestedAtDesc();

    //the runs of an outage, the oldest first (HAS-478) - those linked to its row and, through the run a twin row
    //carried to it (HAS-477), every run linked to that closed twin: the attempts are counted per outage, not per row.
    //No carried run, or a manual one: null matches no row. One query a pass
    @Query("SELECT * FROM battery_charge_run WHERE planned_outage_id = :outageId OR id = :carriedRunId "
        + "OR planned_outage_id = (SELECT planned_outage_id FROM battery_charge_run WHERE id = :carriedRunId) "
        + "ORDER BY requested_at, id")
    Flux<BatteryChargeRunEntity> findOfOutage(Long outageId, Long carriedRunId);

    //the runs requested in [from, to), the oldest first - GET /home/energy/battery/charge/runs
    Flux<BatteryChargeRunEntity> findByRequestedAtGreaterThanEqualAndRequestedAtLessThanOrderByRequestedAt(
        LocalDateTime from, LocalDateTime to);
}

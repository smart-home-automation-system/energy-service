package cloud.cholewa.energy.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface BatteryChargeRunRepository extends ReactiveCrudRepository<BatteryChargeRunEntity, Long> {

    //the newest run - the open one, or the last ended one whose baseline may still be due
    Mono<BatteryChargeRunEntity> findFirstByOrderByRequestedAtDesc();

    //the runs requested in [from, to), the oldest first - GET /home/energy/battery/charge/runs
    Flux<BatteryChargeRunEntity> findByRequestedAtGreaterThanEqualAndRequestedAtLessThanOrderByRequestedAt(
        LocalDateTime from, LocalDateTime to);
}

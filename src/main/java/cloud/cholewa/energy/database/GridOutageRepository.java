package cloud.cholewa.energy.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface GridOutageRepository extends ReactiveCrudRepository<GridOutageEntity, Long> {

    //the outage that lasts - at most one (the partial unique index of V4)
    Mono<GridOutageEntity> findFirstByEndedAtIsNull();

    //the outages that started in [from, to), the oldest first
    Flux<GridOutageEntity> findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAt(
        LocalDateTime from, LocalDateTime to);
}

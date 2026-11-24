package cloud.cholewa.energy.database;

import org.springframework.data.r2dbc.repository.Query;
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

    //whether an outage stored lies on [from, to] - one still open from before to included, the ends touching too
    @Query("SELECT EXISTS (SELECT 1 FROM grid_outage WHERE started_at <= :to AND (ended_at IS NULL OR ended_at >= :from))")
    Mono<Boolean> existsOverlapping(LocalDateTime from, LocalDateTime to);
}

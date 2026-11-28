package cloud.cholewa.energy.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface LoadSheddingRepository extends ReactiveCrudRepository<LoadSheddingEntity, Long> {

    //the episode that lasts - at most one (the partial unique index of V22)
    Mono<LoadSheddingEntity> findFirstByEndedAtIsNull();
}

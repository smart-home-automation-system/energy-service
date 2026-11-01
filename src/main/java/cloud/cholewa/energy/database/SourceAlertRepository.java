package cloud.cholewa.energy.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface SourceAlertRepository extends ReactiveCrudRepository<SourceAlertEntity, Long> {

    //the episodes that last - at most one a source and kind (the partial unique index of V5)
    Flux<SourceAlertEntity> findByClosedAtIsNull();
}

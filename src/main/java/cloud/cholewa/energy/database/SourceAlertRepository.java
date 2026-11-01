package cloud.cholewa.energy.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.Collection;

public interface SourceAlertRepository extends ReactiveCrudRepository<SourceAlertEntity, Long> {

    //the episodes of these kinds that last - at most one a source, kind and detail (the partial unique index of V6)
    Flux<SourceAlertEntity> findByClosedAtIsNullAndKindIn(Collection<String> kinds);
}

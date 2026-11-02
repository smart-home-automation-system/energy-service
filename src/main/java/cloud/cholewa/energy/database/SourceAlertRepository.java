package cloud.cholewa.energy.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.Collection;

public interface SourceAlertRepository extends ReactiveCrudRepository<SourceAlertEntity, Long> {

    //the episodes of these kinds that last - at most one a source, kind and detail (the partial unique index of V6)
    Flux<SourceAlertEntity> findByClosedAtIsNullAndKindIn(Collection<String> kinds);

    //the episodes of one source and kind that last - the alarms of the SOLAX and the faults of the SOFAR apart (HAS-330)
    Flux<SourceAlertEntity> findByClosedAtIsNullAndSourceAndKind(String source, String kind);

    //every episode that lasts, newest first - the open alarms of the dashboard (HAS-341)
    Flux<SourceAlertEntity> findByClosedAtIsNullOrderByOpenedAtDesc();
}

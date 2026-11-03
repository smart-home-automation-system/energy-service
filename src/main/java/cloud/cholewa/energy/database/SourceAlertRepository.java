package cloud.cholewa.energy.database;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.Collection;

public interface SourceAlertRepository extends ReactiveCrudRepository<SourceAlertEntity, Long> {

    //the episodes of these kinds that last - at most one a source, kind and detail (the partial unique index of V6)
    Flux<SourceAlertEntity> findByClosedAtIsNullAndKindIn(Collection<String> kinds);

    //the episodes of one source and kind that last - the alarms of the SOLAX and the faults of the SOFAR apart (HAS-330)
    Flux<SourceAlertEntity> findByClosedAtIsNullAndSourceAndKind(String source, String kind);

    //every episode that lasts, newest first - the open alarms of the dashboard (HAS-341)
    Flux<SourceAlertEntity> findByClosedAtIsNullOrderByOpenedAtDesc();

    //the episodes of a kind that overlap [from, to) - open ones included; the silences of a day of the daily summary
    //(HAS-339)
    @Query("SELECT * FROM source_alert WHERE kind = :kind AND opened_at < :to"
        + " AND (closed_at IS NULL OR closed_at > :from)")
    Flux<SourceAlertEntity> findOverlapping(String kind, LocalDateTime from, LocalDateTime to);
}

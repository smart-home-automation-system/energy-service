package cloud.cholewa.energy.history;

import cloud.cholewa.energy.api.model.HistoryReply;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

//One stored metric from "from" up to, not including, "to", in buckets of "bucketSeconds", oldest first - the query of the
//weather history (HAS-238). measured_at is the wall-clock time of the house without a zone, and the epoch of such a value
//counts from its own midnight, so a bucket that divides a day starts on the clock of the house (00:00, 00:05, ...). A row
//without the metric (NULL) is no reading of it. The database aggregates: a month of a table is up to 44 000 rows, read
//through the index on measured_at (inverter_reading: inverter_id, measured_at) and answered as 372.
//A table or a column cannot be a parameter of a statement, hence the SQL is built here - from StoredValue only, never
//from the request. PostgreSQL only: HistoryRepositoryTest runs it in Docker
@Repository
@RequiredArgsConstructor
public class HistoryRepository {

    private static final String HISTORY = """
        SELECT TIMESTAMP 'epoch'
                   + floor(extract(epoch FROM measured_at) / :bucketSeconds) * :bucketSeconds * INTERVAL '1 second' AS at,
               round(avg(%2$s)::numeric, 2) AS avg,
               min(%2$s)::numeric AS min,
               max(%2$s)::numeric AS max
        FROM %1$s
        WHERE measured_at >= :from
          AND measured_at < :to
          AND %2$s IS NOT NULL%3$s
        GROUP BY at
        ORDER BY at
        """;

    private static final String LAST_BEFORE = """
        SELECT %2$s::numeric AS value
        FROM %1$s
        WHERE measured_at < :from
          AND %2$s IS NOT NULL%3$s
        ORDER BY measured_at DESC
        LIMIT 1
        """;

    private static final String LAST_IN = """
        SELECT %2$s::numeric AS value
        FROM %1$s
        WHERE measured_at >= :from
          AND measured_at < :to
          AND %2$s IS NOT NULL%3$s
        ORDER BY measured_at DESC
        LIMIT 1
        """;

    private static final String MAX_IN = """
        SELECT max(%2$s)::numeric AS value
        FROM %1$s
        WHERE measured_at >= :from
          AND measured_at < :to
          AND %2$s IS NOT NULL%3$s
        """;

    private static final String FIRST_TIME = """
        SELECT min(measured_at) AS at
        FROM %1$s
        WHERE %2$s IS NOT NULL%3$s
        """;

    private static final String OF_INVERTER = "\n  AND inverter_id = :inverterId";

    private final DatabaseClient databaseClient;

    //the buckets of a stored metric; inverterId only for a metric of inverter_reading
    public Flux<HistoryReply.Point> findHistory(
        final StoredValue value,
        final Long inverterId,
        final LocalDateTime from,
        final LocalDateTime to,
        final long bucketSeconds
    ) {
        final DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(HISTORY.formatted(value.table(),
                value.column(), inverterId == null ? "" : OF_INVERTER))
            .bind("bucketSeconds", bucketSeconds)
            .bind("from", from)
            .bind("to", to);
        return (inverterId == null ? spec : spec.bind("inverterId", inverterId))
            .map(row -> new HistoryReply.Point(
                row.get("at", LocalDateTime.class),
                row.get("avg", BigDecimal.class),
                row.get("min", BigDecimal.class),
                row.get("max", BigDecimal.class)))
            .all();
    }

    //the last value of a stored metric before "from" - where a sum carries a part from that has no row in its first
    //buckets (it did not change)
    public Mono<BigDecimal> findLastBefore(final StoredValue value, final Long inverterId, final LocalDateTime from) {
        final DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(LAST_BEFORE.formatted(value.table(),
                value.column(), inverterId == null ? "" : OF_INVERTER))
            .bind("from", from);
        return (inverterId == null ? spec : spec.bind("inverterId", inverterId))
            .map(row -> row.get("value", BigDecimal.class))
            .one();
    }

    //the highest value of a stored metric in [from, to) - the total of a day of a daily counter; empty without a row
    public Mono<BigDecimal> findMaxIn(final StoredValue value, final Long inverterId, final LocalDateTime from,
                                      final LocalDateTime to) {
        final DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(MAX_IN.formatted(value.table(),
                value.column(), inverterId == null ? "" : OF_INVERTER))
            .bind("from", from)
            .bind("to", to);
        return (inverterId == null ? spec : spec.bind("inverterId", inverterId))
            .map(row -> Optional.ofNullable(row.get("value", BigDecimal.class)))
            .one()
            .flatMap(Mono::justOrEmpty);
    }

    //the time of the first row with the value; empty without one
    public Mono<LocalDateTime> findFirstTime(final StoredValue value, final Long inverterId) {
        final DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(FIRST_TIME.formatted(value.table(),
            value.column(), inverterId == null ? "" : OF_INVERTER));
        return (inverterId == null ? spec : spec.bind("inverterId", inverterId))
            .map(row -> Optional.ofNullable(row.get("at", LocalDateTime.class)))
            .one()
            .flatMap(Mono::justOrEmpty);
    }

    //the last value of a stored metric in [from, to) - the total of a day from the daily counters of an inverter, and
    //the proof that the grid meter was read that day (DayCloser)
    public Mono<BigDecimal> findLastIn(final StoredValue value, final Long inverterId, final LocalDateTime from,
                                       final LocalDateTime to) {
        final DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(LAST_IN.formatted(value.table(),
                value.column(), inverterId == null ? "" : OF_INVERTER))
            .bind("from", from)
            .bind("to", to);
        return (inverterId == null ? spec : spec.bind("inverterId", inverterId))
            .map(row -> row.get("value", BigDecimal.class))
            .one();
    }
}

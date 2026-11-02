package cloud.cholewa.energy.history;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

//Every night at 03:20 house time the readings older than energy.history.retention (730 days) are deleted from
//grid_reading, inverter_reading and battery_reading (HAS-331) - never the latest row of a table (of an inverter), which a
//sum of the history carries its value from. In batches of BATCH rows, one table after the other, so a pass never holds a
//connection of the pool of 2 for long; the days of energy_day are kept forever. "Now" is read inside the chain. A failed
//delete is a WARN, tried again the next night; bounded at LIMIT, the next night goes on
@Slf4j
@Component
@RequiredArgsConstructor
public class EnergyRetention {

    static final Duration LIMIT = Duration.ofMinutes(15);
    static final int BATCH = 5000;

    private static final String DELETE_GRID = """
        DELETE FROM grid_reading WHERE id IN (
            SELECT id FROM grid_reading
            WHERE measured_at < :before AND id <> (SELECT max(id) FROM grid_reading)
            ORDER BY id LIMIT :batch)
        """;
    private static final String DELETE_BATTERY = """
        DELETE FROM battery_reading WHERE id IN (
            SELECT id FROM battery_reading
            WHERE measured_at < :before AND id <> (SELECT max(id) FROM battery_reading)
            ORDER BY id LIMIT :batch)
        """;
    private static final String DELETE_INVERTER = """
        DELETE FROM inverter_reading WHERE id IN (
            SELECT id FROM inverter_reading
            WHERE measured_at < :before
              AND id NOT IN (SELECT max(id) FROM inverter_reading GROUP BY inverter_id)
            ORDER BY id LIMIT :batch)
        """;

    private final DatabaseClient databaseClient;
    private final RetentionProperties properties;
    private final Clock clock;

    @Scheduled(cron = "0 20 3 * * *")
    Mono<Void> purge() {
        return ScheduledPass.bounded("energy retention", LIMIT, () -> Mono.defer(this::purgeOnce));
    }

    private Mono<Void> purgeOnce() {
        final LocalDateTime before = LocalDateTime.now(clock).minus(properties.retention());
        return Flux.just(DELETE_GRID, DELETE_INVERTER, DELETE_BATTERY)
            .concatMap(sql -> deleteAll(sql, before))
            .reduce(0L, Long::sum)
            .doOnNext(deleted -> log.info("Energy readings measured before {} deleted: {}", before, deleted))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "Energy readings past the retention could not be deleted, tried again tomorrow: {}", failure.toString())))
            .then();
    }

    //batch after batch until one deletes fewer than BATCH rows
    private Mono<Long> deleteAll(final String sql, final LocalDateTime before) {
        return batch(sql, before)
            .expand(deleted -> Mono.just(deleted).filter(full -> full >= BATCH).flatMap(full -> batch(sql, before)))
            .reduce(0L, Long::sum);
    }

    private Mono<Long> batch(final String sql, final LocalDateTime before) {
        return databaseClient.sql(sql)
            .bind("before", before)
            .bind("batch", BATCH)
            .fetch()
            .rowsUpdated();
    }
}

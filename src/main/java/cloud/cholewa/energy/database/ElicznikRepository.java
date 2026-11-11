package cloud.cholewa.energy.database;

import io.r2dbc.spi.Row;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

//elicznik_hour and elicznik_meter (V15, HAS-340) - SQL by hand: the key of an hour has three columns. A row is inserted
//once and never changed (ON CONFLICT DO NOTHING), so a day stored in part by a pass cut short is completed by the next one.
//Run on PostgreSQL by ElicznikRepositoryTest
@Repository
@RequiredArgsConstructor
public class ElicznikRepository {

    private static final String INSERT_HOUR = """
        INSERT INTO elicznik_hour (day, hour, repeated, imported, exported, fetched_at)
        VALUES (:day, :hour, :repeated, :imported, :exported, :fetchedAt)
        ON CONFLICT (day, hour, repeated) DO NOTHING
        """;
    private static final String INSERT_METER = """
        INSERT INTO elicznik_meter (day, imported, exported, fetched_at)
        VALUES (:day, :imported, :exported, :fetchedAt)
        ON CONFLICT (day) DO NOTHING
        """;
    private static final String HOURS_A_DAY = """
        SELECT day, count(*) AS hours
        FROM elicznik_hour
        WHERE day BETWEEN :from AND :to
        GROUP BY day
        """;
    private static final String METER_DAYS = "SELECT day FROM elicznik_meter WHERE day BETWEEN :from AND :to";
    private static final String HOURS = """
        SELECT day, hour, repeated, imported, exported, fetched_at
        FROM elicznik_hour
        WHERE day BETWEEN :from AND :to
        ORDER BY day, hour, repeated DESC
        """;
    private static final String METERS = """
        SELECT day, imported, exported, fetched_at
        FROM elicznik_meter
        WHERE day BETWEEN :from AND :to
        ORDER BY day
        """;
    private static final String DAYS = """
        SELECT day, sum(imported) AS imported, sum(exported) AS exported
        FROM elicznik_hour
        WHERE day BETWEEN :from AND :to
        GROUP BY day
        ORDER BY day
        """;
    private static final String DAY = "day";
    private static final String FROM = "from";
    private static final String TO = "to";
    private static final String IMPORTED = "imported";
    private static final String EXPORTED = "exported";
    private static final String FETCHED_AT = "fetched_at";

    private final DatabaseClient databaseClient;

    //the stored hours of each day of [from, to] that has some
    public record DayHours(LocalDate day, long hours) {
    }

    //the energy of a day of the portal, the sum of its hours
    public record DayEnergy(LocalDate day, BigDecimal imported, BigDecimal exported) {
    }

    //1 when the hour was written, 0 when it was there
    public Mono<Long> save(final ElicznikHourEntity row) {
        return databaseClient.sql(INSERT_HOUR)
            .bind(DAY, row.day())
            .bind("hour", (short) row.hour())
            .bind("repeated", row.repeated())
            .bind(IMPORTED, row.imported())
            .bind(EXPORTED, row.exported())
            .bind("fetchedAt", row.fetchedAt())
            .fetch()
            .rowsUpdated();
    }

    //1 when the registers were written, 0 when they were there
    public Mono<Long> save(final ElicznikMeterEntity row) {
        return databaseClient.sql(INSERT_METER)
            .bind(DAY, row.day())
            .bind(IMPORTED, row.imported())
            .bind(EXPORTED, row.exported())
            .bind("fetchedAt", row.fetchedAt())
            .fetch()
            .rowsUpdated();
    }

    public Flux<DayHours> hoursADay(final LocalDate from, final LocalDate to) {
        return databaseClient.sql(HOURS_A_DAY)
            .bind(FROM, from)
            .bind(TO, to)
            .map((row, metadata) -> new DayHours(row.get(DAY, LocalDate.class), row.get("hours", Long.class)))
            .all();
    }

    public Flux<LocalDate> meterDays(final LocalDate from, final LocalDate to) {
        return databaseClient.sql(METER_DAYS)
            .bind(FROM, from)
            .bind(TO, to)
            .map((row, metadata) -> row.get(DAY, LocalDate.class))
            .all();
    }

    //the hours of [from, to], by the day and the hour, the repeated hour before the other 3
    public Flux<ElicznikHourEntity> findHours(final LocalDate from, final LocalDate to) {
        return databaseClient.sql(HOURS)
            .bind(FROM, from)
            .bind(TO, to)
            .map((row, metadata) -> new ElicznikHourEntity(
                row.get(DAY, LocalDate.class),
                row.get("hour", Short.class),
                Boolean.TRUE.equals(row.get("repeated", Boolean.class)),
                row.get(IMPORTED, BigDecimal.class),
                row.get(EXPORTED, BigDecimal.class),
                row.get(FETCHED_AT, LocalDateTime.class)))
            .all();
    }

    public Flux<ElicznikMeterEntity> findMeters(final LocalDate from, final LocalDate to) {
        return databaseClient.sql(METERS)
            .bind(FROM, from)
            .bind(TO, to)
            .map((row, metadata) -> new ElicznikMeterEntity(
                row.get(DAY, LocalDate.class),
                row.get(IMPORTED, BigDecimal.class),
                row.get(EXPORTED, BigDecimal.class),
                row.get(FETCHED_AT, LocalDateTime.class)))
            .all();
    }

    //the energy of each stored day of [from, to], by the day
    public Flux<DayEnergy> findDays(final LocalDate from, final LocalDate to) {
        return databaseClient.sql(DAYS)
            .bind(FROM, from)
            .bind(TO, to)
            .map((row, metadata) -> dayEnergy(row))
            .all();
    }

    private static DayEnergy dayEnergy(final Row row) {
        return new DayEnergy(row.get(DAY, LocalDate.class), row.get(IMPORTED, BigDecimal.class),
            row.get(EXPORTED, BigDecimal.class));
    }
}

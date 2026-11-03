package cloud.cholewa.energy.history;

import cloud.cholewa.energy.api.model.HistoryReply;
import cloud.cholewa.energy.database.EnergyDayEntity;
import cloud.cholewa.energy.database.EnergyDayRepository;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//The SQL of HAS-331 on the tables V1-V8 create, in a PostgreSQL in Docker (Testcontainers): the buckets of the history on
//the clock of the house, one inverter apart from the other, the last values a day is closed from, the retention that
//keeps the latest row, a day stored once. Without a Docker the test fails, it is not skipped
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class HistoryRepositoryTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 2, 10, 0);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private EnergyDayRepository energyDayRepository;

    @Autowired
    private SourceAlertRepository sourceAlertRepository;

    private HistoryRepository sut;

    @DynamicPropertySource
    static void connectToTheContainer(final DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
            POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
    }

    @BeforeEach
    void clean() {
        sut = new HistoryRepository(databaseClient);
        execute("DELETE FROM grid_reading");
        execute("DELETE FROM inverter_reading");
        execute("DELETE FROM battery_reading");
        execute("DELETE FROM energy_day");
    }

    private void execute(final String sql) {
        databaseClient.sql(sql).fetch().rowsUpdated().as(StepVerifier::create).expectNextCount(1).verifyComplete();
    }

    private void grid(final LocalDateTime at, final double power, final Double imported) {
        databaseClient.sql("INSERT INTO grid_reading (measured_at, total_power, import_energy) VALUES (:at, :power, :imported)")
            .bind("at", at).bind("power", power)
            .bind("imported", imported == null ? Parameter.empty(Double.class)
                : Parameter.from(imported))
            .fetch().rowsUpdated().as(StepVerifier::create).expectNext(1L).verifyComplete();
    }

    private void inverter(final long id, final LocalDateTime at, final double pv, final double dailyYield) {
        databaseClient.sql("INSERT INTO inverter_reading (inverter_id, measured_at, pv_power, daily_yield) "
                + "VALUES (:id, :at, :pv, :yield)")
            .bind("id", id).bind("at", at).bind("pv", pv).bind("yield", dailyYield)
            .fetch().rowsUpdated().as(StepVerifier::create).expectNext(1L).verifyComplete();
    }

    //the minute of a bucket of 5 minutes on the clock of the house; the grid negative while it exports
    @Test
    void should_answer_the_buckets_on_the_clock_of_the_house() {
        grid(T0.plusMinutes(1), 1000, 10.0);
        grid(T0.plusMinutes(3), 2000, 10.1);
        grid(T0.plusMinutes(6), -500, 10.2);

        sut.findHistory(StoredValue.GRID_POWER, null, T0, T0.plusHours(1), 300).collectList()
            .as(StepVerifier::create)
            .assertNext(points -> assertThat(points).containsExactly(
                new HistoryReply.Point(T0, new BigDecimal("1500.00"), new BigDecimal("1000"), new BigDecimal("2000")),
                new HistoryReply.Point(T0.plusMinutes(5), new BigDecimal("-500.00"), new BigDecimal("-500"),
                    new BigDecimal("-500"))))
            .verifyComplete();
    }

    //every part of a sum in one statement: its buckets, and its last value before the range as a row without "at"
    @Test
    void should_read_every_part_of_a_sum_at_once() {
        inverter(135, T0.minusHours(1), 300, 1.0);
        inverter(134, T0.plusMinutes(1), 1000, 4.0);
        inverter(134, T0.plusMinutes(3), 1200, 4.1);
        grid(T0.plusMinutes(6), -500, 10.0);

        sut.findSum(List.of(new HistoryRepository.SumPart(StoredValue.PV_SOLAX, 134L),
                new HistoryRepository.SumPart(StoredValue.PV_SOFAR, 135L),
                new HistoryRepository.SumPart(StoredValue.GRID_POWER, null)), T0, T0.plusHours(1), 300)
            .collectList()
            .as(StepVerifier::create)
            .assertNext(rows -> assertThat(rows).containsExactlyInAnyOrder(
                new HistoryRepository.SumRow(0, T0, new BigDecimal("1100.00")),
                new HistoryRepository.SumRow(1, null, new BigDecimal("300")),
                new HistoryRepository.SumRow(2, T0.plusMinutes(5), new BigDecimal("-500.00"))))
            .verifyComplete();
    }

    @Test
    void should_read_one_inverter_apart_from_the_other() {
        inverter(134, T0.plusMinutes(1), 1000, 4.0);
        inverter(135, T0.plusMinutes(1), 300, 1.0);

        sut.findHistory(StoredValue.PV_SOFAR, 135L, T0, T0.plusHours(1), 300).collectList()
            .as(StepVerifier::create)
            .assertNext(points -> assertThat(points).extracting(HistoryReply.Point::avg)
                .containsExactly(new BigDecimal("300.00")))
            .verifyComplete();
    }

    @Test
    void should_answer_the_last_values_before_and_within_a_range() {
        inverter(135, T0.minusHours(2), 0, 0.5);
        inverter(135, T0.plusHours(5), 0, 3.25);
        inverter(134, T0.plusHours(6), 0, 9.0);

        sut.findLastBefore(StoredValue.YIELD_SOFAR, 135L, T0).as(StepVerifier::create)
            .assertNext(value -> assertThat(value).isEqualByComparingTo("0.5")).verifyComplete();
        sut.findLastIn(StoredValue.YIELD_SOFAR, 135L, T0, T0.plusDays(1)).as(StepVerifier::create)
            .assertNext(value -> assertThat(value).isEqualByComparingTo("3.25")).verifyComplete();
        sut.findLastIn(StoredValue.YIELD_SOFAR, 135L, T0.plusDays(1), T0.plusDays(2)).as(StepVerifier::create)
            .verifyComplete();
        sut.findFirstTime(StoredValue.YIELD_SOFAR, 135L).as(StepVerifier::create)
            .expectNext(T0.minusHours(2)).verifyComplete();
    }

    //a daily counter the inverter reset a few minutes before midnight of the house: the highest value is the day's total
    @Test
    void should_answer_the_highest_value_of_a_range() {
        final LocalDateTime midnight = LocalDateTime.of(2026, 11, 2, 0, 0);
        inverter(134, midnight.plusMinutes(5), 0, 9.5);
        inverter(134, midnight.plusHours(18), 0, 12.3);
        inverter(134, midnight.plusHours(23).plusMinutes(58), 0, 0.0);

        sut.findMaxIn(StoredValue.YIELD_SOLAX, 134L, midnight.plusMinutes(30), midnight.plusDays(1))
            .as(StepVerifier::create)
            .assertNext(value -> assertThat(value).isEqualByComparingTo("12.3"))
            .verifyComplete();
        sut.findMaxIn(StoredValue.YIELD_SOLAX, 134L, midnight.plusDays(1), midnight.plusDays(2))
            .as(StepVerifier::create)
            .verifyComplete();
    }

    //a row without the value is no reading of it
    @Test
    void should_skip_a_row_without_the_value() {
        grid(T0.plusMinutes(1), 1000, null);

        sut.findLastBefore(StoredValue.GRID_IMPORT, null, T0.plusHours(1)).as(StepVerifier::create).verifyComplete();
        sut.findFirstTime(StoredValue.GRID_IMPORT, null).as(StepVerifier::create).verifyComplete();
    }

    //older than the retention goes, the latest row of a table - of an inverter - stays
    @Test
    void should_delete_the_old_readings_but_the_latest() {
        final LocalDateTime now = LocalDateTime.of(2028, 11, 2, 3, 20);
        grid(now.minusDays(800), 1, 1.0);
        grid(now.minusDays(799), 1, 1.0);
        inverter(134, now.minusDays(800), 1, 1);
        inverter(135, now.minusDays(800), 1, 1);
        inverter(135, now.minusDays(1), 1, 1);
        final Clock clock = Clock.fixed(now.atZone(ZoneId.of("Europe/Warsaw")).toInstant(), ZoneId.of("Europe/Warsaw"));

        new EnergyRetention(databaseClient, new RetentionProperties(Duration.ofDays(730)), clock).purge()
            .as(StepVerifier::create).verifyComplete();

        databaseClient.sql("SELECT count(*) AS n FROM grid_reading").map(row -> row.get("n", Long.class)).one()
            .as(StepVerifier::create).expectNext(1L).verifyComplete();
        databaseClient.sql("SELECT inverter_id, count(*) AS n FROM inverter_reading GROUP BY inverter_id ORDER BY 1")
            .map(row -> row.get("inverter_id", Long.class) + ":" + row.get("n", Long.class)).all()
            .as(StepVerifier::create).expectNext("134:1", "135:1").verifyComplete();
    }

    //HAS-341: the open alarms of the dashboard - the episodes that last, newest first, an ended one left out
    @Test
    void should_answer_the_episodes_that_last_newest_first() {
        execute("DELETE FROM source_alert");
        execute("INSERT INTO source_alert (source, kind, opened_at, last_told_at) VALUES "
            + "('phase b', 'overload', '2026-11-02 10:00', '2026-11-02 10:03')");
        execute("INSERT INTO source_alert (source, kind, opened_at, last_told_at, detail) VALUES "
            + "('sofar', 'alarm', '2026-11-02 12:00', '2026-11-02 12:00', 'GridOVP')");
        execute("INSERT INTO source_alert (source, kind, opened_at, last_told_at, closed_at) VALUES "
            + "('meter', 'silent', '2026-11-02 11:00', '2026-11-02 11:05', '2026-11-02 11:30')");

        sourceAlertRepository.findByClosedAtIsNullOrderByOpenedAtDesc()
            .map(SourceAlertEntity::source)
            .as(StepVerifier::create)
            .expectNext("sofar", "phase b")
            .verifyComplete();
        execute("DELETE FROM source_alert");
    }

    //V9 (HAS-333): an inverter may have an open production episode, without a detail; the meter may not
    @Test
    void should_take_a_production_episode_of_an_inverter_only() {
        execute("DELETE FROM source_alert");
        databaseClient.sql("INSERT INTO source_alert (source, kind, opened_at) VALUES ('sofar', 'production', now())")
            .fetch().rowsUpdated().as(StepVerifier::create).expectNext(1L).verifyComplete();
        databaseClient.sql("INSERT INTO source_alert (source, kind, opened_at) VALUES ('meter', 'production', now())")
            .fetch().rowsUpdated().as(StepVerifier::create).expectError().verify();
        databaseClient.sql("INSERT INTO source_alert (source, kind, opened_at, detail) VALUES ('solax', 'production', "
                + "now(), 'x')")
            .fetch().rowsUpdated().as(StepVerifier::create).expectError().verify();
        databaseClient.sql("INSERT INTO source_alert (source, kind, opened_at) VALUES ('sofar', 'production', now())")
            .fetch().rowsUpdated().as(StepVerifier::create).expectError().verify();
        execute("DELETE FROM source_alert");
    }

    @Test
    void should_store_a_day_once() {
        final EnergyDayEntity day = new EnergyDayEntity(LocalDate.of(2026, 11, 1), 4.0, 1.0, 5.0, 11.2, 0.0, 1.0,
            0.2, 15.4, 1.0, 0.727, 4.0, 11.2, 0.0, T0);

        energyDayRepository.save(day).as(StepVerifier::create).expectNextCount(1).verifyComplete();
        energyDayRepository.save(day).as(StepVerifier::create).expectError().verify();
        energyDayRepository.findLastDay().as(StepVerifier::create).expectNext(LocalDate.of(2026, 11, 1))
            .verifyComplete();
        energyDayRepository.findByDayBetweenOrderByDay(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1))
            .as(StepVerifier::create).expectNext(day).verifyComplete();
    }

    //HAS-339: the silences of a day - an episode that started the day before and one that lasts overlap it, one closed
    //before midnight and one opened after the end do not, nor another kind
    @Test
    void should_answer_the_episodes_of_a_kind_that_overlap_a_range() {
        execute("DELETE FROM source_alert");
        execute("INSERT INTO source_alert (source, kind, opened_at, closed_at) VALUES "
            + "('meter', 'silent', '2026-11-01 22:00', '2026-11-02 03:00')");
        execute("INSERT INTO source_alert (source, kind, opened_at) VALUES ('solax', 'silent', '2026-11-02 12:00')");
        execute("INSERT INTO source_alert (source, kind, opened_at, closed_at) VALUES "
            + "('meter', 'silent', '2026-11-01 10:00', '2026-11-01 23:00')");
        execute("INSERT INTO source_alert (source, kind, opened_at, closed_at) VALUES "
            + "('meter', 'silent', '2026-11-02 21:40', '2026-11-02 21:50')");
        execute("INSERT INTO source_alert (source, kind, opened_at) VALUES ('phase b', 'overload', '2026-11-02 10:00')");

        sourceAlertRepository.findOverlapping("silent", LocalDateTime.of(2026, 11, 2, 0, 0),
                LocalDateTime.of(2026, 11, 2, 21, 30))
            .map(SourceAlertEntity::source)
            .sort()
            .as(StepVerifier::create)
            .expectNext("meter", "solax")
            .verifyComplete();
        execute("DELETE FROM source_alert");
    }
}

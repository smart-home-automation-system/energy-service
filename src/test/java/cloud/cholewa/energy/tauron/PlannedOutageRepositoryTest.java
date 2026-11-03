package cloud.cholewa.energy.tauron;

import cloud.cholewa.energy.database.PlannedOutageEntity;
import cloud.cholewa.energy.database.PlannedOutageRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static cloud.cholewa.energy.tauron.TauronSamples.NOW;
import static cloud.cholewa.energy.tauron.TauronSamples.WORK;
import static org.assertj.core.api.Assertions.assertThat;

//planned_outage of V10 (HAS-337) in a PostgreSQL in Docker: a row stored and read back whole, one open row an id and a
//start, the reads of the watch and of the API. Without a Docker the test fails, it is not skipped
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class PlannedOutageRepositoryTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 12, 8, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 12, 13, 0);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private PlannedOutageRepository repository;

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
        repository.deleteAll().as(StepVerifier::create).verifyComplete();
    }

    private static PlannedOutageEntity fresh(final LocalDateTime start, final LocalDateTime end) {
        return TauronSamples.row(1, TauronSamples.planned(WORK, start, end)).withId(null).withNotice("new");
    }

    @Test
    void should_store_a_row_and_read_it_back_whole() {
        final PlannedOutageEntity row = fresh(START, END).withMovedFrom(START.minusDays(1))
            .withNotifiedAt(NOW).withDayBeforeRemindedAt(NOW).withBeforeStartRemindedAt(NOW);

        repository.save(row)
            .flatMap(saved -> repository.findById(saved.id()))
            .as(StepVerifier::create)
            .assertNext(read -> assertThat(read.withId(null)).isEqualTo(row))
            .verifyComplete();
    }

    @Test
    void should_refuse_a_second_open_row_of_one_outage_but_keep_a_closed_one() {
        repository.save(fresh(START, END).withClosedAt(NOW).withClosedAs("cancelled").withNotice(null))
            .then(repository.save(fresh(START, END)))
            .as(StepVerifier::create)
            .expectNextCount(1)
            .verifyComplete();

        repository.save(fresh(START, END)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
    }

    @Test
    void should_refuse_a_closed_row_without_its_reason_and_an_unknown_notice() {
        repository.save(fresh(START, END).withClosedAt(NOW)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
        repository.save(fresh(START, END).withNotice("reminded")).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
    }

    @Test
    void should_read_the_rows_of_the_watch_and_of_the_api() {
        repository.save(fresh(START, END))
            .then(repository.save(fresh(START.plusDays(1), END.plusDays(1)).withNotice(null)))
            .then(repository.save(fresh(START.minusDays(20), END.minusDays(20)).withNotice(null)))
            .then(repository.save(fresh(START.plusDays(2), END.plusDays(2))
                .withClosedAt(NOW).withClosedAs("cancelled").withNotice("cancelled")))
            .then(repository.save(fresh(START.plusDays(3), END.plusDays(3))
                .withClosedAt(NOW).withClosedAs("moved").withNotice(null)))
            .as(StepVerifier::create)
            .expectNextCount(1)
            .verifyComplete();

        repository.findByClosedAtIsNull().map(PlannedOutageEntity::startsAt).collectList().as(StepVerifier::create)
            .assertNext(starts -> assertThat(starts).containsExactlyInAnyOrder(START, START.plusDays(1),
                START.minusDays(20)))
            .verifyComplete();
        repository.findByClosedAtIsNullOrNoticeIsNotNull().map(PlannedOutageEntity::startsAt).collectList()
            .as(StepVerifier::create)
            .assertNext(starts -> assertThat(starts).containsExactlyInAnyOrder(START, START.plusDays(1),
                START.minusDays(20), START.plusDays(2)))
            .verifyComplete();
        repository.findByClosedAtIsNullAndEndsAtAfterOrderByStartsAt(NOW).map(PlannedOutageEntity::startsAt)
            .collectList().as(StepVerifier::create)
            .assertNext(starts -> assertThat(starts).containsExactly(START, START.plusDays(1)))
            .verifyComplete();
    }
}

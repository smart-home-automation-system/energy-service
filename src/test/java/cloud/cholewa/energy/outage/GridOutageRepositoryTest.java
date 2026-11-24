package cloud.cholewa.energy.outage;

import cloud.cholewa.energy.database.GridOutageEntity;
import cloud.cholewa.energy.database.GridOutageRepository;
import cloud.cholewa.energy.database.MeterUptimeEntity;
import cloud.cholewa.energy.database.MeterUptimeRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;


//V19 (HAS-505) in a PostgreSQL in Docker: a row of the live watch is not inferred, one seen after the fact is; the
//overlap of a range with the outages stored, an open one included; the one row of the meter. Without a Docker the
//test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class GridOutageRepositoryTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 24, 6, 57);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private GridOutageRepository outages;

    @Autowired
    private MeterUptimeRepository uptimes;

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
        outages.deleteAll().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_store_a_live_row_as_not_inferred_and_one_seen_after_the_fact_as_inferred() {
        outages.save(GridOutageEntity.opened(T0, 64.0, T0).closed(T0.plusMinutes(5), 60.0))
            .then(outages.save(GridOutageEntity.inferred(T0.plusDays(1), T0.plusDays(1).plusMinutes(1), T0.plusDays(2))))
            .thenMany(outages.findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAt(T0, T0.plusDays(3)))
            .map(GridOutageEntity::inferred)
            .as(StepVerifier::create)
            .expectNext(false, true)
            .verifyComplete();
    }

    @Test
    void should_find_an_outage_on_a_range_the_ends_touching_and_one_still_open() {
        outages.save(GridOutageEntity.opened(T0, 64.0, T0).closed(T0.plusMinutes(5), 60.0))
            .as(StepVerifier::create).expectNextCount(1).verifyComplete();

        outages.existsOverlapping(T0.plusMinutes(1), T0.plusMinutes(2)).as(StepVerifier::create)
            .expectNext(true).verifyComplete();
        outages.existsOverlapping(T0.plusMinutes(5), T0.plusMinutes(9)).as(StepVerifier::create)
            .expectNext(true).verifyComplete();
        outages.existsOverlapping(T0.minusMinutes(9), T0).as(StepVerifier::create)
            .expectNext(true).verifyComplete();
        outages.existsOverlapping(T0.plusMinutes(6), T0.plusMinutes(9)).as(StepVerifier::create)
            .expectNext(false).verifyComplete();

        outages.save(GridOutageEntity.opened(T0.plusHours(1), 50.0, T0.plusHours(1)))
            .as(StepVerifier::create).expectNextCount(1).verifyComplete();
        outages.existsOverlapping(T0.plusHours(2), T0.plusHours(3)).as(StepVerifier::create)
            .expectNext(true).verifyComplete();
        outages.existsOverlapping(T0.plusMinutes(30), T0.plusMinutes(40)).as(StepVerifier::create)
            .expectNext(false).verifyComplete();
    }

    @Test
    void should_hold_one_row_of_the_meter_never_seen_and_update_it() {
        uptimes.findById(MeterUptimeEntity.ROW_ID).as(StepVerifier::create)
            .expectNext(new MeterUptimeEntity(1, null, null)).verifyComplete();

        final MeterUptimeEntity seen = new MeterUptimeEntity(1, T0, T0.minusHours(14));
        uptimes.save(seen).then(uptimes.findById(1)).as(StepVerifier::create).expectNext(seen).verifyComplete();
        uptimes.count().as(StepVerifier::create).expectNext(1L).verifyComplete();
    }
}

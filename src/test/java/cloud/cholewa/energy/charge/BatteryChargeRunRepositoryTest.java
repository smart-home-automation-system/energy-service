package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.database.BatteryChargeRunEntity;
import cloud.cholewa.energy.database.BatteryChargeRunRepository;
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

import static cloud.cholewa.energy.charge.ChargeSamples.NOW;
import static org.assertj.core.api.Assertions.assertThat;

//battery_charge_run of V11 (HAS-336) in a PostgreSQL in Docker: a run stored and read back whole, one run without its end,
//the checks of its source, target and end, the newest run and the runs of a range. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class BatteryChargeRunRepositoryTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private BatteryChargeRunRepository repository;

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

    private static BatteryChargeRunEntity fresh() {
        return ChargeSamples.charging().withId(null);
    }

    private static BatteryChargeRunEntity ended(final int hoursAgo) {
        return fresh().withRequestedAt(NOW.minusHours(hoursAgo)).withEndedAt(NOW).withEndedBy("target")
            .withSocAtEnd(100.0).withRestoreRequestId("r-2").withRestoredAt(NOW);
    }

    @Test
    void should_store_a_run_and_read_it_back_whole() {
        final BatteryChargeRunEntity run = ended(1).withFailure("DEVICE_FAILED");

        repository.save(run)
            .flatMap(saved -> repository.findById(saved.id()))
            .as(StepVerifier::create)
            .assertNext(read -> assertThat(read.withId(null)).isEqualTo(run))
            .verifyComplete();
    }

    @Test
    void should_refuse_a_second_run_without_its_end() {
        repository.save(ended(2)).then(repository.save(fresh())).as(StepVerifier::create).expectNextCount(1)
            .verifyComplete();

        repository.save(fresh()).as(StepVerifier::create).expectError(DataIntegrityViolationException.class).verify();
    }

    @Test
    void should_refuse_an_unknown_source_a_target_out_of_bounds_and_an_end_without_its_reason() {
        repository.save(fresh().withSource("someone")).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
        repository.save(fresh().withTargetSoc(101)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
        repository.save(fresh().withEndedAt(NOW)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
    }

    @Test
    void should_read_the_newest_run_and_the_runs_of_a_range() {
        repository.save(ended(30)).then(repository.save(ended(5))).then(repository.save(fresh()))
            .as(StepVerifier::create).expectNextCount(1).verifyComplete();

        repository.findFirstByOrderByRequestedAtDesc().as(StepVerifier::create)
            .assertNext(newest -> assertThat(newest.open()).isTrue())
            .verifyComplete();
        repository.findByRequestedAtGreaterThanEqualAndRequestedAtLessThanOrderByRequestedAt(NOW.minusHours(6),
                NOW.plusHours(1))
            .map(BatteryChargeRunEntity::requestedAt)
            .collectList()
            .as(StepVerifier::create)
            .assertNext(times -> assertThat(times).containsExactly(NOW.minusHours(5), NOW))
            .verifyComplete();
    }
}

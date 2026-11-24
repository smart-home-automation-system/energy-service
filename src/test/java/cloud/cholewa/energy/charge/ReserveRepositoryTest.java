package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.database.EnergyReserveEntity;
import cloud.cholewa.energy.database.EnergyReserveRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
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

import static org.assertj.core.api.Assertions.assertThat;

//V16 (HAS-494) in a PostgreSQL in Docker: the one row of the battery reserve, off at 80 % and never written; the level
//and the outcome of a write refused out of their checks. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class ReserveRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 10, 0);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private EnergyReserveRepository reserves;

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

    @Test
    void should_hold_the_reserve_switched_off_and_never_written() {
        reserves.findById(EnergyReserveEntity.ROW_ID).as(StepVerifier::create)
            .assertNext(row -> assertThat(row).isEqualTo(ChargeSamples.reserveNeverWritten()))
            .verifyComplete();
    }

    @Test
    void should_update_the_reserve_and_refuse_it_out_of_its_checks() {
        final EnergyReserveEntity held = new EnergyReserveEntity(1, true, 80, NOW.plusDays(1), NOW, "sent", "r-1", NOW);
        reserves.save(held).then(reserves.findById(1)).as(StepVerifier::create).expectNext(held).verifyComplete();

        reserves.save(held.withLevel(83)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
        reserves.save(held.withLevel(105)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
        reserves.save(held.withWriteOutcome("lost")).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
        reserves.save(ChargeSamples.reserveNeverWritten()).as(StepVerifier::create).expectNextCount(1).verifyComplete();
    }
}

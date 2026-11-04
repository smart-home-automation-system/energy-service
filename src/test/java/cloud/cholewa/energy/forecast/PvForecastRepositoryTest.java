package cloud.cholewa.energy.forecast;

import cloud.cholewa.energy.database.PvForecastEntity;
import cloud.cholewa.energy.database.PvForecastRepository;
import cloud.cholewa.energy.history.EnergyRetentionAccess;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

//pv_forecast (V13, HAS-371) in a PostgreSQL in Docker (Testcontainers): the latest forecast wins while the hour is ahead,
//an hour that started is never changed, a value not given stored as null, the checks of the table, the rows past 60 days
//deleted by the nightly retention. Without a Docker the test fails, it is not skipped
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class PvForecastRepositoryTest {

    private static final LocalDateTime ELEVEN = LocalDateTime.of(2026, 11, 4, 11, 0);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private DatabaseClient databaseClient;

    private PvForecastRepository sut;

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
        sut = new PvForecastRepository(databaseClient);
        databaseClient.sql("DELETE FROM pv_forecast").fetch().rowsUpdated().as(StepVerifier::create)
            .expectNextCount(1).verifyComplete();
    }

    private static PvForecastEntity row(final LocalDateTime hour, final String inverter, final Double irradiance,
                                        final LocalDateTime fetchedAt) {
        return new PvForecastEntity(hour, inverter, irradiance, 74.0, 100.0, 4.9, 0.0, fetchedAt);
    }

    private void save(final PvForecastEntity row, final long written) {
        sut.save(row).as(StepVerifier::create).expectNext(written).verifyComplete();
    }

    @Test
    void should_take_the_latest_forecast_while_the_hour_is_ahead() {
        save(row(ELEVEN, "solax", 60.0, ELEVEN.minusHours(3)), 1);
        save(row(ELEVEN, "sofar", 50.0, ELEVEN.minusHours(3)), 1);
        save(row(ELEVEN, "solax", 80.0, ELEVEN.minusHours(2)), 1);

        sut.findBetween(ELEVEN, ELEVEN.plusHours(1)).collectList()
            .as(StepVerifier::create)
            .assertNext(rows -> assertThat(rows).containsExactly(
                row(ELEVEN, "sofar", 50.0, ELEVEN.minusHours(3)),
                row(ELEVEN, "solax", 80.0, ELEVEN.minusHours(2))))
            .verifyComplete();
    }

    @Test
    void should_never_change_an_hour_that_started() {
        save(row(ELEVEN, "solax", 60.0, ELEVEN.minusHours(1)), 1);
        save(row(ELEVEN, "solax", 90.0, ELEVEN), 0);
        save(row(ELEVEN, "solax", 95.0, ELEVEN.plusMinutes(30)), 0);

        sut.findBetween(ELEVEN, ELEVEN.plusHours(1)).collectList()
            .as(StepVerifier::create)
            .assertNext(rows -> assertThat(rows).containsExactly(row(ELEVEN, "solax", 60.0, ELEVEN.minusHours(1))))
            .verifyComplete();
    }

    @Test
    void should_store_a_value_not_given_as_null() {
        final PvForecastEntity empty = new PvForecastEntity(ELEVEN, "sofar", null, null, null, null, null,
            ELEVEN.minusHours(1));
        save(empty, 1);

        sut.findBetween(ELEVEN, ELEVEN.plusHours(1)).collectList()
            .as(StepVerifier::create)
            .assertNext(rows -> assertThat(rows).containsExactly(empty))
            .verifyComplete();
    }

    @Test
    void should_refuse_an_inverter_that_is_not_one_of_the_planes() {
        sut.save(row(ELEVEN, "garage", 60.0, ELEVEN.minusHours(1)))
            .as(StepVerifier::create)
            .expectError()
            .verify();
    }

    @Test
    void should_delete_the_hours_older_than_sixty_days_at_night() {
        final LocalDateTime night = LocalDateTime.of(2026, 11, 4, 3, 20);
        save(row(night.minusDays(60).minusHours(1), "solax", 10.0, night.minusDays(61)), 1);
        save(row(night.minusDays(59), "solax", 20.0, night.minusDays(60)), 1);

        EnergyRetentionAccess.purge(databaseClient, Clock.fixed(night.atZone(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault()))
            .as(StepVerifier::create)
            .verifyComplete();

        sut.findBetween(night.minusDays(90), night).collectList()
            .as(StepVerifier::create)
            .assertNext(rows -> assertThat(rows).extracting(PvForecastEntity::irradiance).containsExactly(20.0))
            .verifyComplete();
    }
}

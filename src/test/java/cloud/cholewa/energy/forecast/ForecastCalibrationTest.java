package cloud.cholewa.energy.forecast;

import cloud.cholewa.energy.database.EnergyDayEntity;
import cloud.cholewa.energy.database.EnergyDayRepository;
import cloud.cholewa.energy.database.PvForecastCalibrationEntity;
import cloud.cholewa.energy.database.PvForecastCalibrationRepository;
import cloud.cholewa.energy.database.PvForecastDayEntity;
import cloud.cholewa.energy.database.PvForecastDayRepository;
import cloud.cholewa.energy.database.PvForecastEntity;
import cloud.cholewa.energy.database.PvForecastRepository;
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
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.groups.Tuple.tuple;

//The calibration on the closed days (HAS-372), against V14 in a PostgreSQL in Docker (Testcontainers): a day becomes a
//row with the forecast at 1.0 and the production, the ratio is the sum over the sum on enough days, the initial one
//below them, clamped, a day with an hour missing or without a production left out, the 25 hours of the end of summer
//time, a second pass that changes nothing, the window. Without a Docker the test fails, it is not skipped
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class ForecastCalibrationTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    //the SOLAX: 10 kWp, the SOFAR 5 kWp - a day of 100 W/m2 every hour of 24 is 24 kWh / 12 kWh at 1.0
    private static final ForecastProperties FORECAST = new ForecastProperties(URI.create("http://localhost:1"),
        Duration.ofHours(1), new ForecastProperties.Plane(35.0, 0.0, 10.0), new ForecastProperties.Plane(37.0, 0.0, 5.0));
    private static final OutlookProperties OUTLOOK = new OutlookProperties(0.8, 28, 5, Duration.ofHours(3),
        new ForecastRules.Bands(0.10, 0.40, 0.80));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 1, 30);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private DatabaseClient databaseClient;
    @Autowired
    private EnergyDayRepository energyDayRepository;

    private PvForecastRepository pvForecastRepository;
    private PvForecastDayRepository pvForecastDayRepository;
    private PvForecastCalibrationRepository calibrationRepository;
    private final Instant[] now = {NOW.atZone(HOUSE).toInstant()};

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
        pvForecastRepository = new PvForecastRepository(databaseClient);
        pvForecastDayRepository = new PvForecastDayRepository(databaseClient);
        calibrationRepository = new PvForecastCalibrationRepository(databaseClient);
        Flux.just("pv_forecast", "pv_forecast_day", "pv_forecast_calibration", "energy_day")
            .concatMap(table -> databaseClient.sql("DELETE FROM " + table).fetch().rowsUpdated())
            .then()
            .as(StepVerifier::create)
            .verifyComplete();
    }

    private ForecastCalibration sut() {
        final Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return HOUSE;
            }

            @Override
            public Clock withZone(final ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        return new ForecastCalibration(energyDayRepository, pvForecastRepository, pvForecastDayRepository,
            calibrationRepository, FORECAST, OUTLOOK, clock);
    }

    //a closed day with the productions of both inverters (null - not known)
    private void closedDay(final LocalDate day, final Double solax, final Double sofar) {
        energyDayRepository.save(new EnergyDayEntity(day, solax, sofar, null, null, null, null, null, 20.0, null, null,
                null, null, null, day.plusDays(1).atTime(0, 20)))
            .as(StepVerifier::create)
            .expectNextCount(1)
            .verifyComplete();
    }

    //the forecast of the hours of the day (house times, the 02:00 that does not exist in spring left out) for both
    //planes, 100 W/m2; skip - an hour of the SOLAX not stored
    private void forecastDay(final LocalDate day, final int hours, final LocalDateTime skip) {
        Flux.range(0, hours)
            .map(hour -> day.atStartOfDay().plusHours(hour))
            .filter(hour -> !HOUSE.getRules().getValidOffsets(hour).isEmpty())
            .concatMap(hour -> Flux.just("solax", "sofar")
                .filter(inverter -> !(inverter.equals("solax") && hour.equals(skip)))
                .concatMap(inverter -> pvForecastRepository.save(new PvForecastEntity(hour, inverter, 100.0, 50.0,
                    80.0, 3.0, 0.0, hour.minusHours(2)))))
            .then()
            .as(StepVerifier::create)
            .verifyComplete();
    }

    private void calibrate() {
        sut().calibrate().as(StepVerifier::create).verifyComplete();
    }

    private List<PvForecastDayEntity> days() {
        final List<PvForecastDayEntity> days = new ArrayList<>();
        pvForecastDayRepository.findBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31))
            .as(StepVerifier::create)
            .recordWith(() -> days)
            .thenConsumeWhile(row -> true)
            .verifyComplete();
        return days;
    }

    private List<PvForecastCalibrationEntity> calibrations() {
        final List<PvForecastCalibrationEntity> calibrations = new ArrayList<>();
        calibrationRepository.findAll()
            .as(StepVerifier::create)
            .recordWith(() -> calibrations)
            .thenConsumeWhile(row -> true)
            .verifyComplete();
        return calibrations;
    }

    //five days of the house: 24 kWh forecast for the SOLAX, 12 for the SOFAR, at 1.0
    private void fiveDays(final double solax, final double sofar) {
        for (int back = 1; back <= 5; back++) {
            final LocalDate day = NOW.toLocalDate().minusDays(back);
            closedDay(day, solax, sofar);
            forecastDay(day, 24, null);
        }
    }

    @Test
    void should_calibrate_each_inverter_on_its_closed_days() {
        fiveDays(14.4, 9.0);

        calibrate();

        assertThat(days()).hasSize(10)
            .allSatisfy(row -> assertThat(row.calculatedAt()).isEqualTo(NOW))
            .filteredOn(row -> row.inverter().equals("solax"))
            .allSatisfy(row -> {
                assertThat(row.forecastKwh()).isCloseTo(24.0, within(1e-9));
                assertThat(row.actualKwh()).isEqualTo(14.4);
            });
        assertThat(calibrations()).satisfiesExactly(
            sofar -> {
                assertThat(sofar.inverter()).isEqualTo("sofar");
                assertThat(sofar.ratio()).isCloseTo(0.75, within(1e-9));
                assertThat(sofar.days()).isEqualTo(5);
            },
            solax -> {
                assertThat(solax.inverter()).isEqualTo("solax");
                assertThat(solax.ratio()).isCloseTo(0.6, within(1e-9));
                assertThat(solax.days()).isEqualTo(5);
                assertThat(solax.calibratedAt()).isEqualTo(NOW);
            });
    }

    @Test
    void should_keep_the_initial_ratio_below_the_fewest_days() {
        for (int back = 1; back <= 4; back++) {
            final LocalDate day = NOW.toLocalDate().minusDays(back);
            closedDay(day, 14.4, 9.0);
            forecastDay(day, 24, null);
        }

        calibrate();

        assertThat(days()).hasSize(8);
        assertThat(calibrations()).extracting(PvForecastCalibrationEntity::ratio, PvForecastCalibrationEntity::days)
            .containsExactly(tuple(0.8, 0), tuple(0.8, 0));
    }

    @Test
    void should_clamp_the_ratio() {
        fiveDays(30.0, 1.0);

        calibrate();

        assertThat(calibrations()).extracting(PvForecastCalibrationEntity::inverter, PvForecastCalibrationEntity::ratio)
            .containsExactly(tuple("sofar", 0.3),
                tuple("solax", 1.0));
    }

    @Test
    void should_leave_out_a_day_with_an_hour_missing_or_without_a_production() {
        final LocalDate missingHour = NOW.toLocalDate().minusDays(1);
        closedDay(missingHour, 14.4, 9.0);
        forecastDay(missingHour, 24, missingHour.atTime(12, 0));
        final LocalDate noSofar = NOW.toLocalDate().minusDays(2);
        closedDay(noSofar, 14.4, null);
        forecastDay(noSofar, 24, null);
        final LocalDate noForecast = NOW.toLocalDate().minusDays(3);
        closedDay(noForecast, 14.4, 9.0);

        calibrate();

        assertThat(days()).extracting(PvForecastDayEntity::day, PvForecastDayEntity::inverter)
            .containsExactlyInAnyOrder(tuple(missingHour, "sofar"),
                tuple(noSofar, "solax"));
    }

    //the 25 hours of the end of summer time are 24 times of the house - the doubled 02:00 is one row of pv_forecast
    @Test
    void should_calibrate_the_day_summer_time_ends() {
        now[0] = LocalDateTime.of(2026, 10, 26, 1, 30).atZone(HOUSE).toInstant();
        final LocalDate autumn = LocalDate.of(2026, 10, 25);
        closedDay(autumn, 14.4, 9.0);
        forecastDay(autumn, 24, null);

        calibrate();

        assertThat(days()).hasSize(2);
    }

    //its 02:00 does not exist: 23 hours make the day whole
    @Test
    void should_calibrate_the_day_summer_time_starts() {
        now[0] = LocalDateTime.of(2027, 3, 29, 1, 30).atZone(HOUSE).toInstant();
        final LocalDate spring = LocalDate.of(2027, 3, 28);
        closedDay(spring, 14.4, 9.0);
        forecastDay(spring, 24, null);

        calibrate();

        assertThat(days()).hasSize(2)
            .allSatisfy(row -> assertThat(row.forecastKwh()).isIn(23 * 1.0, 23 * 0.5));
    }

    @Test
    void should_change_nothing_on_a_second_pass() {
        fiveDays(14.4, 9.0);
        calibrate();
        now[0] = now[0].plus(Duration.ofHours(1));

        calibrate();

        assertThat(days()).hasSize(10).allSatisfy(row -> assertThat(row.calculatedAt()).isEqualTo(NOW));
        assertThat(calibrations()).allSatisfy(row -> assertThat(row.calibratedAt()).isEqualTo(NOW));
    }

    @Test
    void should_calibrate_on_the_days_of_the_window_only() {
        fiveDays(14.4, 9.0);
        final LocalDate old = NOW.toLocalDate().minusDays(29);
        closedDay(old, 24.0, 12.0);
        forecastDay(old, 24, null);

        calibrate();

        assertThat(days()).hasSize(10).noneMatch(row -> row.day().equals(old));
        assertThat(calibrations()).extracting(PvForecastCalibrationEntity::ratio)
            .satisfiesExactly(sofar -> assertThat(sofar).isCloseTo(0.75, within(1e-9)),
                solax -> assertThat(solax).isCloseTo(0.6, within(1e-9)));
    }

    //the next closed day moves the ratio and the time it was calibrated at
    @Test
    void should_recalibrate_with_the_next_closed_day() {
        fiveDays(14.4, 9.0);
        calibrate();
        final LocalDateTime tomorrow = NOW.plusDays(1);
        now[0] = tomorrow.atZone(HOUSE).toInstant();
        closedDay(NOW.toLocalDate(), 24.0, 9.0);
        forecastDay(NOW.toLocalDate(), 24, null);

        calibrate();

        //the SOLAX: (5 x 14.4 + 24) / (6 x 24) = 0.667
        assertThat(calibrations()).filteredOn(row -> row.inverter().equals("solax")).singleElement()
            .satisfies(solax -> {
                assertThat(solax.ratio()).isCloseTo(96.0 / 144, within(1e-9));
                assertThat(solax.days()).isEqualTo(6);
                assertThat(solax.calibratedAt()).isEqualTo(tomorrow);
            });
    }

    //the house load of GET /forecast: the last seven closed days before today, newest first
    @Test
    void should_read_the_last_seven_closed_days() {
        for (int back = 0; back <= 9; back++) {
            closedDay(NOW.toLocalDate().minusDays(back), 1.0, 1.0);
        }

        energyDayRepository.findTop7ByDayLessThanOrderByDayDesc(NOW.toLocalDate())
            .map(EnergyDayEntity::day)
            .as(StepVerifier::create)
            .expectNext(NOW.toLocalDate().minusDays(1), NOW.toLocalDate().minusDays(2), NOW.toLocalDate().minusDays(3),
                NOW.toLocalDate().minusDays(4), NOW.toLocalDate().minusDays(5), NOW.toLocalDate().minusDays(6),
                NOW.toLocalDate().minusDays(7))
            .verifyComplete();
    }
}

package cloud.cholewa.energy.automation;

import cloud.cholewa.energy.database.BatteryChargeRunEntity;
import cloud.cholewa.energy.database.BatteryChargeRunRepository;
import cloud.cholewa.energy.database.EnergyAutomationEntity;
import cloud.cholewa.energy.database.EnergyAutomationRepository;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//V12 (HAS-338) in a PostgreSQL in Docker: the one row of the settings with its defaults and checks, the skip and the run of
//an outage written by their own statements, the next outage of the street not skipped, an automatic run linked to its
//outage. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class AutomationRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 14, 0);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private EnergyAutomationRepository settings;
    @Autowired
    private PlannedOutageRepository outages;
    @Autowired
    private BatteryChargeRunRepository runs;

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
        runs.deleteAll().then(outages.deleteAll()).as(StepVerifier::create).verifyComplete();
    }

    private static PlannedOutageEntity outage(final String id, final String scope, final int hoursAhead) {
        return new PlannedOutageEntity(null, id, "planned", scope, NOW.plusHours(hoursAhead),
            NOW.plusHours(hoursAhead + 4), "Testowo", NOW, NOW, null, null, NOW, null, null, null, null, false, null);
    }

    //the switch off until the owner turns it on (the task)
    @Test
    void should_hold_the_settings_with_the_switch_off_by_default() {
        settings.findById(EnergyAutomationEntity.ROW_ID).as(StepVerifier::create)
            .assertNext(row -> assertThat(row).isEqualTo(new EnergyAutomationEntity(1, false, 100, 5.0, 60, null)))
            .verifyComplete();
    }

    @Test
    void should_update_the_settings_and_refuse_them_out_of_bounds() {
        final EnergyAutomationEntity on = new EnergyAutomationEntity(1, true, 90, 4.5, 30, NOW);
        settings.save(on).then(settings.findById(1)).as(StepVerifier::create).expectNext(on).verifyComplete();

        settings.save(on.withChargePowerKw(8.5)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
        settings.save(on.withTargetSoc(100).withChargePowerKw(5.0).withMarginMinutes(60).withChargeBeforeOutage(false)
            .withUpdatedAt(null)).as(StepVerifier::create).expectNextCount(1).verifyComplete();
    }

    @Test
    void should_find_the_next_outage_of_the_street_not_skipped() {
        outages.save(outage("town", "town", 2))
            .then(outages.save(outage("later", "street", 10)))
            .then(outages.save(outage("next", "street", 5)))
            .flatMap(next -> outages.setSkipAutomation(next.id(), true).thenReturn(next))
            .as(StepVerifier::create).expectNextCount(1).verifyComplete();

        outages.findFirstByClosedAtIsNullAndKindAndScopeAndSkipAutomationFalseAndStartsAtAfterOrderByStartsAt("planned",
                "street", NOW)
            .map(PlannedOutageEntity::outageId)
            .as(StepVerifier::create)
            .expectNext("later")
            .verifyComplete();
    }

    @Test
    void should_skip_only_an_outage_still_announced() {
        outages.save(outage("closed", "street", 5).withClosedAt(NOW).withClosedAs("cancelled"))
            .flatMap(closed -> outages.setSkipAutomation(closed.id(), true))
            .as(StepVerifier::create)
            .expectNext(0)
            .verifyComplete();
    }

    @Test
    void should_link_an_automatic_run_and_its_outage() {
        outages.save(outage("next", "street", 5))
            .flatMap(next -> runs.save(new BatteryChargeRunEntity(null, NOW, "automation", 100, NOW, NOW.plusHours(5),
                    "r-1", null, null, 45.0, null, null, null, null, null, null, next.id()))
                .flatMap(run -> outages.setChargeRun(next.id(), run.id())
                    .then(Mono.defer(() -> runs.existsByPlannedOutageId(next.id())))
                    .flatMap(exists -> outages.findById(next.id())
                        .map(read -> exists && run.id().equals(read.chargeRunId())))))
            .as(StepVerifier::create)
            .expectNext(true)
            .verifyComplete();
    }
}

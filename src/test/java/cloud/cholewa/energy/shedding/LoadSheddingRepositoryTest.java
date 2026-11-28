package cloud.cholewa.energy.shedding;

import cloud.cholewa.energy.database.EnergyAutomationEntity;
import cloud.cholewa.energy.database.EnergyAutomationRepository;
import cloud.cholewa.energy.database.LoadSheddingEntity;
import cloud.cholewa.energy.database.LoadSheddingRepository;
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

//V22 (HAS-523) in a PostgreSQL in Docker: the switch on by default, at most one open episode, the end and the reason of
//an end together and only the two reasons. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class LoadSheddingRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 28, 12, 0);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private LoadSheddingRepository episodes;
    @Autowired
    private EnergyAutomationRepository settings;

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
        episodes.deleteAll().as(StepVerifier::create).verifyComplete();
    }

    //on by default (owner, 2026-11-25)
    @Test
    void should_hold_the_switch_on_by_default() {
        settings.findById(EnergyAutomationEntity.ROW_ID)
            .map(EnergyAutomationEntity::loadShedding)
            .as(StepVerifier::create)
            .expectNext(true)
            .verifyComplete();
    }

    @Test
    void should_hold_at_most_one_open_episode_and_find_it() {
        episodes.save(LoadSheddingEntity.started(NOW.minusHours(3)).ended(NOW.minusHours(2), LoadSheddingEntity.BY_GRID))
            .then(episodes.save(LoadSheddingEntity.started(NOW)))
            .then(episodes.findFirstByEndedAtIsNull())
            .map(LoadSheddingEntity::startedAt)
            .as(StepVerifier::create)
            .expectNext(NOW)
            .verifyComplete();

        episodes.save(LoadSheddingEntity.started(NOW.plusMinutes(1))).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
    }

    @Test
    void should_refuse_an_end_without_its_reason_or_with_another_one() {
        episodes.save(new LoadSheddingEntity(null, NOW, NOW.plusMinutes(5), null)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
        episodes.save(new LoadSheddingEntity(null, NOW, null, LoadSheddingEntity.BY_SWITCH)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
        episodes.save(LoadSheddingEntity.started(NOW).ended(NOW.plusMinutes(5), "battery")).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
    }
}

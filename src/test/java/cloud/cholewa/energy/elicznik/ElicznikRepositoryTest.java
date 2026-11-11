package cloud.cholewa.energy.elicznik;

import cloud.cholewa.energy.database.ElicznikHourEntity;
import cloud.cholewa.energy.database.ElicznikMeterEntity;
import cloud.cholewa.energy.database.ElicznikRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//elicznik_hour and elicznik_meter (V15, HAS-340) on PostgreSQL: a row inserted once and never changed, the two hours 3 of
//the night the clocks go back, the checks of the table, the sums of a day
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import(ElicznikRepository.class)
class ElicznikRepositoryTest {

    private static final LocalDate BACK = LocalDate.of(2026, 10, 25);
    private static final LocalDateTime FETCHED = LocalDateTime.of(2026, 10, 26, 6, 0);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private ElicznikRepository repository;

    @Autowired
    private DatabaseClient databaseClient;

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
        databaseClient.sql("DELETE FROM elicznik_hour").then()
            .then(databaseClient.sql("DELETE FROM elicznik_meter").then())
            .as(StepVerifier::create).verifyComplete();
    }

    private static ElicznikHourEntity hour(final LocalDate day, final int hour, final boolean repeated,
                                           final String imported) {
        return new ElicznikHourEntity(day, hour, repeated, new BigDecimal(imported), new BigDecimal("0.010"), FETCHED);
    }

    @Test
    void should_keep_the_two_hours_3_of_the_night_the_clocks_go_back_and_insert_once() {
        repository.save(hour(BACK, 3, true, "1.392")).as(StepVerifier::create).expectNext(1L).verifyComplete();
        repository.save(hour(BACK, 3, false, "1.363")).as(StepVerifier::create).expectNext(1L).verifyComplete();
        repository.save(hour(BACK, 3, false, "9.999")).as(StepVerifier::create).expectNext(0L).verifyComplete();

        repository.findHours(BACK, BACK).collectList().as(StepVerifier::create)
            .assertNext(hours -> assertThat(hours).extracting(ElicznikHourEntity::repeated, ElicznikHourEntity::imported)
                .containsExactly(
                    org.assertj.core.groups.Tuple.tuple(true, new BigDecimal("1.392")),
                    org.assertj.core.groups.Tuple.tuple(false, new BigDecimal("1.363"))))
            .verifyComplete();
        repository.hoursADay(BACK, BACK).as(StepVerifier::create)
            .expectNext(new ElicznikRepository.DayHours(BACK, 2))
            .verifyComplete();
    }

    @Test
    void should_sum_the_hours_of_each_day() {
        repository.save(hour(BACK, 1, false, "1.000")).then(repository.save(hour(BACK, 2, false, "0.250")))
            .then(repository.save(hour(BACK.plusDays(1), 1, false, "2.000")))
            .as(StepVerifier::create).expectNextCount(1).verifyComplete();

        repository.findDays(BACK, BACK.plusDays(1)).as(StepVerifier::create)
            .expectNext(new ElicznikRepository.DayEnergy(BACK, new BigDecimal("1.250"), new BigDecimal("0.020")))
            .expectNext(new ElicznikRepository.DayEnergy(BACK.plusDays(1), new BigDecimal("2.000"),
                new BigDecimal("0.010")))
            .verifyComplete();
    }

    //only hour 3 may be repeated
    @Test
    void should_refuse_another_repeated_hour() {
        repository.save(hour(BACK, 4, true, "1.000")).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
    }

    @Test
    void should_store_the_registers_once() {
        final ElicznikMeterEntity registers = new ElicznikMeterEntity(BACK, new BigDecimal("2587"), new BigDecimal("4868"),
            FETCHED);

        repository.save(registers).as(StepVerifier::create).expectNext(1L).verifyComplete();
        repository.save(registers).as(StepVerifier::create).expectNext(0L).verifyComplete();

        repository.meterDays(BACK, BACK).as(StepVerifier::create).expectNext(BACK).verifyComplete();
        repository.findMeters(BACK, BACK).as(StepVerifier::create)
            .assertNext(found -> assertThat(found.imported()).isEqualByComparingTo("2587"))
            .verifyComplete();
    }
}

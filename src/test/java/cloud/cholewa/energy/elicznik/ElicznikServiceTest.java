package cloud.cholewa.energy.elicznik;

import cloud.cholewa.energy.api.model.ElicznikReply;
import cloud.cholewa.energy.database.ElicznikHourEntity;
import cloud.cholewa.energy.database.ElicznikMeterEntity;
import cloud.cholewa.energy.database.ElicznikRepository;
import cloud.cholewa.energy.error.EnergyException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//GET /home/energy/elicznik (HAS-340): the stored days with their sums, hours and registers; the range at most 31 days
class ElicznikServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 8);
    private static final LocalDateTime FETCHED = LocalDateTime.of(2026, 11, 9, 6, 0);

    private final ElicznikRepository repository = mock(ElicznikRepository.class);
    private final ElicznikService sut = new ElicznikService(repository);

    @Test
    void should_answer_the_days_with_their_sums_hours_and_registers() {
        when(repository.findHours(DAY, DAY)).thenReturn(Flux.just(
            new ElicznikHourEntity(DAY, 1, false, new BigDecimal("1.558"), BigDecimal.ZERO, FETCHED),
            new ElicznikHourEntity(DAY, 2, false, new BigDecimal("1.377"), new BigDecimal("0.010"), FETCHED)));
        when(repository.findMeters(DAY, DAY)).thenReturn(Flux.just(
            new ElicznikMeterEntity(DAY, new BigDecimal("2777"), new BigDecimal("4872"), FETCHED)));

        sut.days(DAY, DAY).as(StepVerifier::create)
            .expectNext(new ElicznikReply(DAY, DAY, List.of(new ElicznikReply.Day(DAY, new BigDecimal("2.935"),
                new BigDecimal("0.010"), new BigDecimal("2777"), new BigDecimal("4872"), List.of(
                    new ElicznikReply.Hour(1, false, new BigDecimal("1.558"), BigDecimal.ZERO),
                    new ElicznikReply.Hour(2, false, new BigDecimal("1.377"), new BigDecimal("0.010")))))))
            .verifyComplete();
    }

    @Test
    void should_leave_out_the_registers_not_stored() {
        when(repository.findHours(DAY, DAY)).thenReturn(Flux.just(
            new ElicznikHourEntity(DAY, 1, false, BigDecimal.ONE, BigDecimal.ZERO, FETCHED)));
        when(repository.findMeters(DAY, DAY)).thenReturn(Flux.empty());

        sut.days(DAY, DAY).as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.days().getFirst().meterImported()).isNull())
            .verifyComplete();
    }

    @Test
    void should_refuse_a_range_longer_than_31_days() {
        sut.days(DAY, DAY.plusDays(31)).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOf(EnergyException.class)
                .hasMessage("The range must not be longer than 31 days"))
            .verify();
    }

    @Test
    void should_refuse_a_reversed_range() {
        sut.days(DAY, DAY.minusDays(1)).as(StepVerifier::create)
            .expectErrorMessage("from must not be after to")
            .verify();
    }
}

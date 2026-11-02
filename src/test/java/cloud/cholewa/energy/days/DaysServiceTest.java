package cloud.cholewa.energy.days;

import cloud.cholewa.energy.api.model.DayReply;
import cloud.cholewa.energy.api.model.MonthsReply;
import cloud.cholewa.energy.database.EnergyDayEntity;
import cloud.cholewa.energy.database.EnergyDayRepository;
import cloud.cholewa.energy.error.EnergyError;
import cloud.cholewa.energy.error.EnergyException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the closed days and the billing year (HAS-331): the year from September, every closed day before it counting for the
//credit, the months without a day written out with days 0
class DaysServiceTest {

    private final EnergyDayRepository repository = mock(EnergyDayRepository.class);
    private final DaysService service = new DaysService(repository, new SettlementProperties(new BigDecimal("0.7"), 9));

    private static EnergyDayEntity day(final LocalDate day, final Double imported, final Double exported) {
        return new EnergyDayEntity(day, 4.0, 1.0, 5.0, imported, exported, 1.0, 0.2, 15.4, 1.0, 0.727, null, null, null,
            day.plusDays(1).atStartOfDay());
    }

    @Test
    void should_answer_the_closed_days() {
        final LocalDate day = LocalDate.of(2026, 11, 1);
        when(repository.findByDayBetweenOrderByDay(day, day)).thenReturn(Flux.just(day(day, 11.2, 0.0)));

        service.days(day, day).as(StepVerifier::create)
            .expectNext(List.of(new DayReply(day, 4.0, 1.0, 5.0, 11.2, 0.0, 1.0, 0.2, 15.4, 1.0, 0.727, null, null,
                null)))
            .verifyComplete();
    }

    @Test
    void should_refuse_a_reversed_or_too_long_range() {
        final LocalDate from = LocalDate.of(2026, 1, 1);

        service.days(from, from.minusDays(1)).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((EnergyException) failure).getError())
                .isEqualTo(EnergyError.INVALID_RANGE))
            .verify();
        service.days(from, from.plusDays(366)).as(StepVerifier::create)
            .expectErrorMessage("The range must not be longer than 366 days")
            .verify();
        verify(repository, never()).findByDayBetweenOrderByDay(any(), any());
    }

    //the billing year 2026 is September 2026 to August 2027; the credit of August 2026 carries into it
    @Test
    void should_settle_the_billing_year_from_september() {
        when(repository.findByDayLessThanEqualOrderByDay(LocalDate.of(2027, 8, 31))).thenReturn(Flux.just(
            day(LocalDate.of(2026, 8, 31), 0.0, 100.0),
            day(LocalDate.of(2026, 9, 1), 10.0, 0.0),
            day(LocalDate.of(2026, 9, 2), 5.0, null)));

        service.months(2026).as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.from()).isEqualTo(YearMonth.of(2026, 9));
                assertThat(reply.to()).isEqualTo(YearMonth.of(2027, 8));
                assertThat(reply.ratio()).isEqualByComparingTo("0.7");
                assertThat(reply.months()).hasSize(12);
                final MonthsReply.Month september = reply.months().getFirst();
                assertThat(september.days()).isEqualTo(2);
                assertThat(september.imported()).isEqualByComparingTo("15");
                assertThat(september.exported()).isEqualByComparingTo("0");
                assertThat(september.production()).isEqualByComparingTo("10");
                assertThat(september.credited()).isEqualByComparingTo("0");
                assertThat(september.drawn()).isEqualByComparingTo("15");
                assertThat(september.stored()).isEqualByComparingTo("55");
                final MonthsReply.Month october = reply.months().get(1);
                assertThat(october.days()).isZero();
                assertThat(october.imported()).isNull();
                assertThat(october.stored()).isEqualByComparingTo("55");
                assertThat(reply.months().getLast().month()).isEqualTo(YearMonth.of(2027, 8));
                assertThat(reply.months().getLast().expired()).isEqualByComparingTo("55");
            })
            .verifyComplete();
    }

    @Test
    void should_answer_a_year_without_a_day() {
        when(repository.findByDayLessThanEqualOrderByDay(any())).thenReturn(Flux.empty());

        service.months(2030).as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.months()).allSatisfy(month -> {
                assertThat(month.days()).isZero();
                assertThat(month.stored()).isEqualByComparingTo("0");
            }))
            .verifyComplete();
    }

    @Test
    void should_refuse_a_year_out_of_the_calendar() {
        service.months(1999).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((EnergyException) failure).getError())
                .isEqualTo(EnergyError.INVALID_RANGE))
            .verify();
    }
}

package cloud.cholewa.energy.days;

import cloud.cholewa.energy.database.EnergyDayEntity;
import cloud.cholewa.energy.database.EnergyDayRepository;
import cloud.cholewa.energy.history.StoredValue;
import cloud.cholewa.energy.history.StoredValues;
import cloud.cholewa.energy.solaxcloud.CloudDays;
import cloud.cholewa.energy.solaxcloud.CloudDaysFeeder;
import cloud.cholewa.energy.solaxcloud.CloudSamples;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the closing of the days (HAS-331): every day over and not closed, from the stored readings - the daily counters of the
//inverters and the battery, the counters of the grid meter at both midnights - with the cross-check of the SolaX cloud
class DayCloserTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    //2026-11-02 00:30 in the house: 2026-11-01 is over
    private static final Clock CLOCK = Clock.fixed(LocalDateTime.of(2026, 11, 2, 0, 30).atZone(HOUSE).toInstant(), HOUSE);
    private static final LocalDate DAY = LocalDate.of(2026, 11, 1);
    private static final LocalDateTime START = DAY.atStartOfDay();
    private static final LocalDateTime END = START.plusDays(1);
    //the daily counters of the inverters are read after their first 30 minutes
    private static final LocalDateTime SETTLED = START.plusMinutes(30);

    private final EnergyDayRepository repository = mock(EnergyDayRepository.class);
    private final StoredValues storedValues = mock(StoredValues.class);
    private final CloudDays cloudDays = new CloudDays();
    private final DayCloser closer = new DayCloser(repository, storedValues, cloudDays, CLOCK);

    private static Mono<BigDecimal> kwh(final String value) {
        return Mono.just(new BigDecimal(value));
    }

    @BeforeEach
    void setUp() {
        when(repository.findLastDay()).thenReturn(Mono.just(DAY.minusDays(1)));
        when(repository.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(storedValues.lastIn(any(), any(), any())).thenReturn(Mono.empty());
        when(storedValues.maxIn(any(), any(), any())).thenReturn(Mono.empty());
        when(storedValues.lastBefore(any(), any())).thenReturn(Mono.empty());
        when(storedValues.maxIn(StoredValue.YIELD_SOLAX, SETTLED, END)).thenReturn(kwh("4.0"));
        when(storedValues.maxIn(StoredValue.YIELD_SOFAR, SETTLED, END)).thenReturn(kwh("1.0"));
        when(storedValues.lastIn(StoredValue.GRID_IMPORT, START, END)).thenReturn(kwh("891.20"));
        when(storedValues.lastBefore(StoredValue.GRID_IMPORT, START)).thenReturn(kwh("880.00"));
        when(storedValues.lastIn(StoredValue.GRID_EXPORT, START, END)).thenReturn(kwh("5388.50"));
        when(storedValues.lastBefore(StoredValue.GRID_EXPORT, START)).thenReturn(kwh("5388.50"));
        when(storedValues.maxIn(StoredValue.BATTERY_CHARGED_TODAY, SETTLED, END)).thenReturn(kwh("1.0"));
        when(storedValues.maxIn(StoredValue.BATTERY_DISCHARGED_TODAY, SETTLED, END)).thenReturn(kwh("0.2"));
    }

    @Test
    void should_close_the_day_that_is_over() {
        closer.close().as(StepVerifier::create).verifyComplete();

        verify(repository).save(new EnergyDayEntity(DAY, 4.0, 1.0, 5.0, 11.2, 0.0, 1.0, 0.2, 15.4, 1.0, 0.727, null,
            null, null, LocalDateTime.of(2026, 11, 2, 0, 30)));
    }

    //the SolaX cloud of 2026-11-01 (its plant answer of 12:55) is the cross-check
    @Test
    void should_store_the_day_of_the_cloud() {
        CloudDaysFeeder.feed(cloudDays, CloudSamples.inverter(), CloudSamples.plant());

        closer.close().as(StepVerifier::create).verifyComplete();

        verify(repository).save(argThat(row -> row.cloudYield() == 4.0 && row.cloudImported() == 11.2
            && row.cloudExported() == 0.0));
    }

    //without a reading of the meter before the day (the first day of the service) imported and exported are not known
    @Test
    void should_leave_out_the_grid_without_its_counters_at_midnight() {
        when(storedValues.lastBefore(StoredValue.GRID_IMPORT, START)).thenReturn(Mono.empty());

        closer.close().as(StepVerifier::create).verifyComplete();

        verify(repository).save(argThat(row -> row.imported() == null && row.houseLoad() == null
            && row.exported() == 0.0 && row.production() == 5.0));
    }

    //after a downtime every missed day, oldest first
    @Test
    void should_close_every_day_a_downtime_missed() {
        when(repository.findLastDay()).thenReturn(Mono.just(DAY.minusDays(3)));

        closer.close().as(StepVerifier::create).verifyComplete();

        verify(repository, times(3)).save(any());
        verify(repository).save(argThat(row -> row.day().equals(DAY.minusDays(2))));
    }

    //at 00:10 the last readings of yesterday may not be stored yet: it is closed only after 00:15
    @Test
    void should_not_close_a_day_right_after_its_midnight() {
        final Clock early = Clock.fixed(LocalDateTime.of(2026, 11, 2, 0, 10).atZone(HOUSE).toInstant(), HOUSE);

        new DayCloser(repository, storedValues, cloudDays, early).close().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).save(any());
    }

    @Test
    void should_close_nothing_while_today_is_not_over() {
        when(repository.findLastDay()).thenReturn(Mono.just(DAY));

        closer.close().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).save(any());
    }

    //the first pass of a new database: from the day of the first reading of the grid meter
    @Test
    void should_start_from_the_first_reading_of_the_meter() {
        when(repository.findLastDay()).thenReturn(Mono.empty());
        when(storedValues.firstTime(StoredValue.GRID_IMPORT)).thenReturn(Mono.just(LocalDateTime.of(2026, 10, 31, 14, 2)));

        closer.close().as(StepVerifier::create).verifyComplete();

        verify(repository, times(2)).save(any());
    }

    @Test
    void should_close_nothing_without_a_reading() {
        when(repository.findLastDay()).thenReturn(Mono.empty());
        when(storedValues.firstTime(StoredValue.GRID_IMPORT)).thenReturn(Mono.empty());

        closer.close().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).save(any());
    }

    //never further back than MAX_DAYS_BACK
    @Test
    void should_go_back_at_most_a_month() {
        when(repository.findLastDay()).thenReturn(Mono.just(LocalDate.of(2025, 1, 1)));

        closer.close().as(StepVerifier::create).verifyComplete();

        verify(repository, times(DayCloser.MAX_DAYS_BACK)).save(any());
    }

    @Test
    void should_try_again_with_the_next_pass_when_the_database_fails() {
        doReturn(Mono.error(new IllegalStateException("database down"))).when(repository).save(any());

        closer.close().as(StepVerifier::create).verifyComplete();

        verify(repository).save(any());
    }

    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        doReturn(Mono.never()).when(repository).findLastDay();

        StepVerifier.withVirtualTime(closer::close)
            .expectSubscription()
            .expectNoEvent(DayCloser.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }
}

package cloud.cholewa.energy.meter;

import cloud.cholewa.energy.database.GridReadingEntity;
import cloud.cholewa.energy.database.GridReadingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;

import static cloud.cholewa.energy.meter.MeterSamples.counters;
import static cloud.cholewa.energy.meter.MeterSamples.readings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GridSnapshotTest {

    private final MeterSamples.MovableClock clock = new MeterSamples.MovableClock();
    private final GridMeterTracker tracker = new GridMeterTracker(clock);
    private final GridReadingRepository repository = mock(GridReadingRepository.class);
    private final GridSnapshot snapshot = new GridSnapshot(tracker, repository);

    @Test
    void should_write_nothing_before_the_first_readings() {
        tracker.counters(counters(891011.75, 2158851.5));

        snapshot.snapshot().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).save(any());
    }

    //the powers as the meter sends them, the energies from its Wh counters in kWh
    @Test
    void should_write_the_readings_with_the_counters_in_kwh() {
        saves();
        tracker.readings(readings(-113.5));
        tracker.counters(counters(891011.75, 2158851.5));

        snapshot.snapshot().as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<GridReadingEntity> row = ArgumentCaptor.forClass(GridReadingEntity.class);
        verify(repository).save(row.capture());
        assertThat(row.getValue()).isEqualTo(new GridReadingEntity(null, LocalDateTime.of(2026, 10, 27, 11, 0),
            -171.4, 31.2, 26.7, -113.5, 891.01175, 2158.8515, 236.8, 235.4, 234.6));
    }

    //the counters come once a minute: the readings are written without them until they came
    @Test
    void should_write_the_readings_without_the_energies_the_meter_did_not_send_yet() {
        saves();
        tracker.readings(readings(-113.5));

        snapshot.snapshot().as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<GridReadingEntity> row = ArgumentCaptor.forClass(GridReadingEntity.class);
        verify(repository).save(row.capture());
        assertThat(row.getValue().importEnergy()).isNull();
        assertThat(row.getValue().exportEnergy()).isNull();
    }

    //a status table inserts on a change only: the same values a minute later - a meter that fell silent, or a
    //quiet house - write no row
    @Test
    void should_write_a_row_only_when_a_value_changed() {
        saves();
        tracker.readings(readings(-113.5));
        final Mono<Void> pass = snapshot.snapshot();

        pass.as(StepVerifier::create).verifyComplete();
        clock.moveBy(Duration.ofMinutes(1));
        tracker.readings(readings(-113.5));
        pass.as(StepVerifier::create).verifyComplete();
        verify(repository, times(1)).save(any());

        tracker.readings(readings(120.0));
        pass.as(StepVerifier::create).verifyComplete();
        verify(repository, times(2)).save(any());
    }

    //a write that failed is not taken for one done: the next minute writes the same values
    @Test
    void should_write_again_after_a_failed_write() {
        when(repository.save(any())).thenReturn(Mono.error(new IllegalStateException("connection lost")))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        tracker.readings(readings(-113.5));
        final Mono<Void> pass = snapshot.snapshot();

        pass.as(StepVerifier::create).verifyComplete();
        pass.as(StepVerifier::create).verifyComplete();

        verify(repository, times(2)).save(any());
    }

    @Test
    void should_end_a_write_that_never_completes_at_its_bound() {
        when(repository.save(any())).thenReturn(Mono.never());
        tracker.readings(readings(-113.5));

        StepVerifier.withVirtualTime(snapshot::snapshot)
            .expectSubscription()
            .expectNoEvent(GridSnapshot.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }

    //the counters of the meter of the house on 2026-10-27: divided, 891057.42 Wh is 891.0574200000001 kWh
    @Test
    void should_turn_wh_into_kwh_without_a_tail_of_the_binary_fraction() {
        assertThat(GridSnapshot.kwh(891057.42)).isEqualTo(891.05742);
        assertThat(GridSnapshot.kwh(2158891.98)).isEqualTo(2158.89198);
        assertThat(GridSnapshot.kwh(null)).isNull();
    }

    private void saves() {
        when(repository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }
}

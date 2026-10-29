package cloud.cholewa.energy.solax;

import cloud.cholewa.energy.database.BatteryReadingEntity;
import cloud.cholewa.energy.database.BatteryReadingRepository;
import cloud.cholewa.energy.database.InverterReadingEntity;
import cloud.cholewa.energy.database.InverterReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;

import static cloud.cholewa.energy.solax.SolaxSamples.EARLIER_EVENING;
import static cloud.cholewa.energy.solax.SolaxSamples.EVENING;
import static cloud.cholewa.energy.solax.SolaxSamples.ID;
import static cloud.cholewa.energy.solax.SolaxSamples.reading;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SolaxSnapshotTest {

    private static final LocalDateTime READ_AT = LocalDateTime.of(2026, 10, 27, 20, 49);

    private final SolaxSamples.MovableClock clock = new SolaxSamples.MovableClock();
    private final SolaxTracker tracker = new SolaxTracker(clock);
    private final InverterReadingRepository inverterRepository = mock(InverterReadingRepository.class);
    private final BatteryReadingRepository batteryRepository = mock(BatteryReadingRepository.class);
    private final SolaxSnapshot snapshot = new SolaxSnapshot(tracker, inverterRepository, batteryRepository);

    @BeforeEach
    void setUp() {
        when(inverterRepository.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(batteryRepository.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
    }

    @Test
    void should_write_nothing_before_the_first_reading() {
        snapshot.snapshot().as(StepVerifier::create).verifyComplete();

        verify(inverterRepository, never()).save(any());
        verify(batteryRepository, never()).save(any());
    }

    //at night the panels give nothing: 0 W, not left out
    @Test
    void should_write_the_inverter_and_the_battery() {
        tracker.read(ID, reading(EVENING));

        snapshot.snapshot().as(StepVerifier::create).verifyComplete();

        verify(inverterRepository).save(new InverterReadingEntity(null, ID, READ_AT, 0.0, 0.0, 0.0, 1755.0,
            "normal", 48.0, 38.6));
        verify(batteryRepository).save(new BatteryReadingEntity(null, READ_AT, 64.0, -1980.0, 52.3, -38.6, 20.0));
    }

    //a reading repeated unchanged writes no row, a silent SOLAX writes nothing more
    @Test
    void should_write_a_row_only_when_a_value_changed() {
        tracker.read(ID, reading(EVENING));
        snapshot.snapshot().as(StepVerifier::create).verifyComplete();
        clock.moveBy(Duration.ofMinutes(1));
        tracker.read(ID, reading(EVENING));
        snapshot.snapshot().as(StepVerifier::create).verifyComplete();
        clock.moveBy(Duration.ofMinutes(1));
        tracker.read(ID, reading(EARLIER_EVENING));
        snapshot.snapshot().as(StepVerifier::create).verifyComplete();

        verify(inverterRepository, times(2)).save(any());
        verify(batteryRepository, times(2)).save(any());
    }

    //a failed write is tried again with the next minute; the battery is written even when the inverter failed
    @Test
    void should_try_a_failed_write_again_with_the_next_minute() {
        tracker.read(ID, reading(EVENING));
        doReturn(Mono.error(new IllegalStateException("no connection")),
            Mono.just(new InverterReadingEntity(1L, ID, READ_AT, 0.0, 0.0, 0.0, 1755.0, "normal", 48.0, 38.6)))
            .when(inverterRepository).save(any());

        snapshot.snapshot().as(StepVerifier::create).verifyComplete();
        snapshot.snapshot().as(StepVerifier::create).verifyComplete();

        verify(inverterRepository, times(2)).save(any());
        verify(batteryRepository).save(any());
    }

    @Test
    void should_end_a_snapshot_that_never_completes_at_its_bound() {
        tracker.read(ID, reading(EVENING));
        doReturn(Mono.never()).when(inverterRepository).save(any());

        StepVerifier.withVirtualTime(snapshot::snapshot)
            .expectSubscription()
            .expectNoEvent(SolaxSnapshot.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }
}

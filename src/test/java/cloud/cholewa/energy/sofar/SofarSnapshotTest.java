package cloud.cholewa.energy.sofar;

import cloud.cholewa.energy.database.InverterReadingEntity;
import cloud.cholewa.energy.database.InverterReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the rows of inverter_reading of the SOFAR (HAS-330): at the time the logger collected the reading, only when a value
//changed, run_mode NULL
class SofarSnapshotTest {

    private final SofarTracker tracker = mock(SofarTracker.class);
    private final InverterReadingRepository repository = mock(InverterReadingRepository.class);
    private final SofarSnapshot snapshot = new SofarSnapshot(tracker, repository);

    @BeforeEach
    void setUp() {
        when(repository.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
    }

    @Test
    void should_write_nothing_before_the_first_reading() {
        when(tracker.current()).thenReturn(new SofarState(null, null, null, null));

        snapshot.snapshot().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).save(any());
    }

    @Test
    void should_write_the_reading_once_until_a_value_changes() {
        when(tracker.current()).thenReturn(SofarSamples.state(SofarSamples.reading(), true),
            SofarSamples.state(SofarSamples.reading(), true),
            SofarSamples.state(SofarSamples.withFaults(List.of("GridOVP")), true));

        snapshot.snapshot().as(StepVerifier::create).verifyComplete();
        snapshot.snapshot().as(StepVerifier::create).verifyComplete();
        snapshot.snapshot().as(StepVerifier::create).verifyComplete();

        verify(repository).save(new InverterReadingEntity(null, 135L, SofarSamples.COLLECTED_AT, 200.0, 270.0, 0.0,
            270.0, null, 32.0, 0.26));
        verify(repository, times(1)).save(any());
    }

    @Test
    void should_try_a_failed_write_again_with_the_next_minute() {
        when(tracker.current()).thenReturn(SofarSamples.state(SofarSamples.reading(), true));
        doReturn(Mono.error(new IllegalStateException("database down")))
            .doAnswer(call -> Mono.just(call.getArgument(0)))
            .when(repository).save(any());

        snapshot.snapshot().as(StepVerifier::create).verifyComplete();
        snapshot.snapshot().as(StepVerifier::create).verifyComplete();

        verify(repository, times(2)).save(any());
    }

    @Test
    void should_end_a_snapshot_that_never_completes_at_its_bound() {
        when(tracker.current()).thenReturn(SofarSamples.state(SofarSamples.reading(), true));
        doReturn(Mono.never()).when(repository).save(any());

        StepVerifier.withVirtualTime(snapshot::snapshot)
            .expectSubscription()
            .expectNoEvent(SofarSnapshot.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }
}

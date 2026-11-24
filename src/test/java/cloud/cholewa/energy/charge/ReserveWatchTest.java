package cloud.cholewa.energy.charge;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static cloud.cholewa.energy.charge.ChargeSamples.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the watch of the battery reserve (HAS-494): the same Mono of the scheduler subscribed once a minute sees the time of
//each run, and a failed read is logged, never an error of the pass
class ReserveWatchTest {

    private final ChargeFixture fixture = new ChargeFixture();
    private final ReserveWatch watch = new ReserveWatch(fixture.reserve, fixture.commands);

    @Test
    void should_see_the_time_of_every_run_of_the_same_pass() {
        fixture.reserveRow.set(ChargeSamples.reserveHeld().withUntil(NOW.plusMinutes(1)));
        final Mono<Void> scheduled = watch.watch();

        scheduled.as(StepVerifier::create).verifyComplete();
        assertThat(fixture.reserveRow.get().enabled()).isTrue();

        fixture.moveTo(NOW.plusMinutes(1));
        scheduled.as(StepVerifier::create).verifyComplete();

        assertThat(fixture.reserveRow.get().enabled()).isFalse();
    }

    @Test
    void should_complete_a_pass_whose_read_failed() {
        when(fixture.reserves.findById(1)).thenReturn(Mono.error(new IllegalStateException("database down")));

        watch.watch().as(StepVerifier::create).verifyComplete();

        verify(fixture.client, never()).setSelfUse(any());
        verify(fixture.client, never()).setBackUp(any());
    }
}

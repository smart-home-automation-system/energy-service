package cloud.cholewa.energy.status;

import cloud.cholewa.energy.api.model.SourceStatusReply;
import cloud.cholewa.energy.meter.GridMeterTracker;
import cloud.cholewa.energy.meter.GridMeterState;
import cloud.cholewa.shelly.model.EmDataStatus;
import cloud.cholewa.shelly.model.EmStatus;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnergyStatusServiceTest {

    private final GridMeterTracker tracker = mock(GridMeterTracker.class);
    private final EnergyStatusService energyStatusService = new EnergyStatusService(tracker);

    //after a start nothing is known: every source is there, in the order of the dashboard, and none is known
    @Test
    void should_answer_every_source_as_not_known_before_anything_was_heard() {
        when(tracker.current()).thenReturn(new GridMeterState(null, null, null, null, null));

        energyStatusService.status()
            .as(StepVerifier::create)
            .expectNext(List.of(
                new SourceStatusReply("meter", null, null),
                new SourceStatusReply("solax", null, null),
                new SourceStatusReply("sofar", null, null),
                new SourceStatusReply("solaxCloud", null, null)
            ))
            .verifyComplete();
    }

    //the meter with its newest reading - the counters came after the readings here
    @Test
    void should_answer_the_meter_with_its_newest_reading() {
        when(tracker.current()).thenReturn(new GridMeterState(new EmStatus(), LocalDateTime.of(2026, 10, 27, 11, 0),
            new EmDataStatus(), LocalDateTime.of(2026, 10, 27, 11, 0, 30), true));

        energyStatusService.status()
            .as(StepVerifier::create)
            .expectNextMatches(sources -> sources.getFirst()
                .equals(new SourceStatusReply("meter", true, LocalDateTime.of(2026, 10, 27, 11, 0, 30))))
            .verifyComplete();
    }

    //its last will: not reachable, with the time of what it said last
    @Test
    void should_answer_a_meter_that_left_the_broker_as_not_reachable() {
        when(tracker.current()).thenReturn(new GridMeterState(new EmStatus(), LocalDateTime.of(2026, 10, 27, 11, 0),
            null, null, false));

        energyStatusService.status()
            .as(StepVerifier::create)
            .expectNextMatches(sources -> sources.getFirst()
                .equals(new SourceStatusReply("meter", false, LocalDateTime.of(2026, 10, 27, 11, 0))))
            .verifyComplete();
    }
}

package cloud.cholewa.energy.status;

import cloud.cholewa.energy.api.model.SourceStatusReply;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

class EnergyStatusServiceTest {

    private final EnergyStatusService energyStatusService = new EnergyStatusService();

    //nothing is read yet (HAS-325): every source is there, in the order of the dashboard, and none is known
    @Test
    void should_answer_every_source_as_not_known() {
        energyStatusService.status()
            .as(StepVerifier::create)
            .expectNext(List.of(
                new SourceStatusReply("meter", null),
                new SourceStatusReply("solax", null),
                new SourceStatusReply("sofar", null),
                new SourceStatusReply("solaxCloud", null)
            ))
            .verifyComplete();
    }
}

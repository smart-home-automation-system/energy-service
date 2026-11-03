package cloud.cholewa.energy.tauron;

import cloud.cholewa.energy.automation.AutomationPlanFixture;
import cloud.cholewa.energy.database.PlannedOutageEntity;
import cloud.cholewa.energy.database.PlannedOutageRepository;
import cloud.cholewa.energy.error.EnergyError;
import cloud.cholewa.energy.error.EnergyException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.LocalDateTime;

import static cloud.cholewa.energy.tauron.TauronSamples.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//the planned outages for the API (HAS-337, HAS-338): the coming ones with the planned start of the automatic charge on the
//one it is planned for, the skip of an outage still announced
class PlannedOutageServiceTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 12, 8, 0);
    private final PlannedOutageRepository repository = mock(PlannedOutageRepository.class);
    private final AutomationPlanFixture plan = new AutomationPlanFixture();
    private final PlannedOutageService service = new PlannedOutageService(repository, new OutageLane(), plan.plan(),
        Clock.fixed(NOW.atZone(TauronSamples.HOUSE).toInstant(), TauronSamples.HOUSE));

    private static PlannedOutageEntity outage(final long id) {
        return TauronSamples.told(id, START.plusDays(id), START.plusDays(id).plusHours(5));
    }

    @Test
    void should_answer_the_planned_start_on_the_outage_it_is_planned_for() {
        when(repository.findByClosedAtIsNullAndEndsAtAfterOrderByStartsAt(NOW)).thenReturn(Flux.just(outage(3), outage(4)));
        plan.set(3L, START.plusDays(3), START.plusDays(3).minusHours(4));

        service.planned().as(StepVerifier::create)
            .assertNext(outages -> {
                assertThat(outages.get(0).id()).isEqualTo(3L);
                assertThat(outages.get(0).plannedChargeStart()).isEqualTo(START.plusDays(3).minusHours(4));
                assertThat(outages.get(1).plannedChargeStart()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void should_skip_an_outage_still_announced() {
        when(repository.setSkipAutomation(3L, true)).thenReturn(Mono.just(1));
        when(repository.findById(3L)).thenReturn(Mono.just(outage(3).withSkipAutomation(true)));

        service.skip(3L, true).as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.skipAutomation()).isTrue())
            .verifyComplete();
    }

    @Test
    void should_refuse_the_skip_of_an_outage_not_announced() {
        when(repository.setSkipAutomation(9L, false)).thenReturn(Mono.just(0));

        service.skip(9L, false).as(StepVerifier::create)
            .verifyErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(EnergyException.class,
                refused -> assertThat(refused.getError()).isEqualTo(EnergyError.NOT_FOUND_PLANNED_OUTAGE)));
    }
}

package cloud.cholewa.energy.automation;

import cloud.cholewa.energy.api.model.AutomationReply;
import cloud.cholewa.energy.api.model.AutomationRequest;
import cloud.cholewa.energy.database.EnergyAutomationEntity;
import cloud.cholewa.energy.database.EnergyAutomationRepository;
import cloud.cholewa.energy.error.EnergyError;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.solax.SolaxSamples;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the settings of the automatic charge (HAS-338): the one row read and replaced whole, refused out of bounds, a database
//without the row a failed read
class AutomationServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 15, 0);
    private final EnergyAutomationRepository repository = mock(EnergyAutomationRepository.class);
    private final AutomationService service = new AutomationService(repository,
        Clock.fixed(NOW.atZone(SolaxSamples.HOUSE).toInstant(), SolaxSamples.HOUSE));

    @BeforeEach
    void setUp() {
        when(repository.findById(EnergyAutomationEntity.ROW_ID)).thenReturn(Mono.just(
            new EnergyAutomationEntity(1, false, 100, 5.0, 60, null)));
        when(repository.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
    }

    @Test
    void should_answer_the_settings() {
        service.settings().as(StepVerifier::create)
            .expectNext(new AutomationReply(false, 100, 5.0, 60, null))
            .verifyComplete();
    }

    @Test
    void should_replace_the_settings_whole() {
        service.replace(new AutomationRequest(true, 90, 4.5, 30)).as(StepVerifier::create)
            .expectNext(new AutomationReply(true, 90, 4.5, 30, NOW))
            .verifyComplete();
        verify(repository).save(new EnergyAutomationEntity(1, true, 90, 4.5, 30, NOW));
    }

    @Test
    void should_refuse_settings_out_of_bounds_and_store_nothing() {
        service.replace(new AutomationRequest(true, 90, 9.0, 30)).as(StepVerifier::create)
            .verifyErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(EnergyException.class,
                refused -> assertThat(refused.getError()).isEqualTo(EnergyError.INVALID_AUTOMATION)));
        verify(repository, never()).save(any());
    }

    @Test
    void should_fail_a_read_of_a_database_without_the_row() {
        when(repository.findById(EnergyAutomationEntity.ROW_ID)).thenReturn(Mono.empty());

        service.settings().as(StepVerifier::create)
            .verifyErrorMessage("energy_automation has no row");
    }
}

package cloud.cholewa.energy.inverter;

import cloud.cholewa.energy.api.model.InverterReply;
import cloud.cholewa.energy.api.model.PhaseReply;
import cloud.cholewa.energy.api.model.PvReply;
import cloud.cholewa.energy.error.EnergyError;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.registry.InverterRegistry;
import cloud.cholewa.energy.solax.RunMode;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.solax.SolaxSamples;
import cloud.cholewa.energy.solax.SolaxState;
import cloud.cholewa.energy.solax.SolaxTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static cloud.cholewa.energy.solax.SolaxSamples.EVENING;
import static cloud.cholewa.energy.solax.SolaxSamples.ID;
import static cloud.cholewa.energy.solax.SolaxSamples.reading;
import static cloud.cholewa.energy.solax.SolaxSamples.sofar;
import static cloud.cholewa.energy.solax.SolaxSamples.solax;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InverterServiceTest {

    private static final LocalDateTime READ_AT = LocalDateTime.of(2026, 10, 27, 20, 49);

    private final SolaxSamples.MovableClock clock = new SolaxSamples.MovableClock();
    private final InverterRegistry registry = mock(InverterRegistry.class);
    private final SolaxTracker tracker = mock(SolaxTracker.class);
    private final InverterService service = new InverterService(registry, tracker,
        new SolaxProperties(Duration.ofSeconds(15), Duration.ofMinutes(1), 26.5), clock);

    @BeforeEach
    void setUp() {
        when(registry.current()).thenReturn(Optional.of(List.of(solax("192.0.2.158"), sofar())));
        when(tracker.current()).thenReturn(new SolaxState(ID, reading(EVENING), READ_AT, true));
    }

    //the SOLAX with its reading, the SOFAR with its registry entry only until its task
    @Test
    void should_answer_every_inverter_of_the_registry() {
        service.inverters().as(StepVerifier::create)
            .expectNext(List.of(solaxReply(false), new InverterReply("sofar", 135L, "SOFAR inverter", "boiler",
                null, null, null, null, null, null, null, null, null, null, null, null, true)))
            .verifyComplete();
    }

    @Test
    void should_answer_one_inverter_by_its_brand() {
        service.inverter("solax").as(StepVerifier::create)
            .expectNext(solaxReply(false))
            .verifyComplete();
    }

    //older than energy.solax.stale-after: the values are served, marked stale
    @Test
    void should_serve_an_old_reading_as_stale() {
        clock.moveBy(Duration.ofSeconds(61));

        service.inverter("solax").as(StepVerifier::create)
            .expectNext(solaxReply(true))
            .verifyComplete();
    }

    @Test
    void should_serve_the_reading_of_a_failed_read_as_stale() {
        when(tracker.current()).thenReturn(new SolaxState(ID, reading(EVENING), READ_AT, false));

        service.inverter("solax").as(StepVerifier::create)
            .expectNext(solaxReply(true))
            .verifyComplete();
    }

    //after a start, before the first reading: the registry entry and stale
    @Test
    void should_answer_the_registry_entry_before_the_first_reading() {
        when(tracker.current()).thenReturn(new SolaxState(null, null, null, null));

        service.inverter("solax").as(StepVerifier::create)
            .expectNext(new InverterReply("solax", ID, "SOLAX inverter", "garage", null, null, null, null, null, null,
                null, null, null, null, null, null, true))
            .verifyComplete();
    }

    @Test
    void should_refuse_an_inverter_not_known() {
        service.inverter("fronius").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOfSatisfying(EnergyException.class, refused -> {
                    assertThat(refused.getError()).isEqualTo(EnergyError.NOT_FOUND_INVERTER);
                    assertThat(refused.getMessage()).isEqualTo("No inverter fronius is registered");
                }))
            .verify();
    }

    //a brand the registry has no inverter of
    @Test
    void should_refuse_an_inverter_not_registered() {
        when(registry.current()).thenReturn(Optional.of(List.of(solax("192.0.2.158"))));

        service.inverter("sofar").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((EnergyException) failure).getError())
                .isEqualTo(EnergyError.NOT_FOUND_INVERTER))
            .verify();
    }

    @Test
    void should_refuse_while_the_registry_is_not_known() {
        when(registry.current()).thenReturn(Optional.empty());

        service.inverters().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((EnergyException) failure).getError())
                .isEqualTo(EnergyError.REGISTRY_UNAVAILABLE))
            .verify();
        service.inverter("solax").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((EnergyException) failure).getError())
                .isEqualTo(EnergyError.REGISTRY_UNAVAILABLE))
            .verify();
    }

    private static InverterReply solaxReply(final boolean stale) {
        final Map<String, PhaseReply> phases = new LinkedHashMap<>();
        phases.put("a", new PhaseReply(234.0, 2.1, 479.0, null, null, null));
        phases.put("b", new PhaseReply(232.7, 3.6, 817.0, null, null, null));
        phases.put("c", new PhaseReply(234.5, 2.1, 460.0, null, null, null));
        final Map<String, PvReply> pv = new LinkedHashMap<>();
        pv.put("pv1", new PvReply(0.0, 0.0, 0.0));
        pv.put("pv2", new PvReply(0.0, 0.0, 0.0));
        return new InverterReply("solax", ID, "SOLAX inverter", "garage", RunMode.NORMAL, 1755.0, phases, 0.0, pv,
            0.0, 50.01, 0.95, 48.0, 38.6, 4510.6, READ_AT, stale);
    }
}

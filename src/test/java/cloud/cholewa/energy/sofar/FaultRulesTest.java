package cloud.cholewa.energy.sofar;

import cloud.cholewa.energy.database.SourceAlertEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//what the faults of the SOFAR are to the household (HAS-330): a new fault one alert since the time of its reading, a
//fault the reading no longer has one info, a held one nothing
class FaultRulesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 10, 22);

    private static SourceAlertEntity held(final String fault, final LocalDateTime openedAt) {
        return new SourceAlertEntity(7L, "sofar", "alarm", openedAt, openedAt, null, fault);
    }

    @Test
    void should_decide_nothing_without_faults() {
        final FaultRules.Decision decision = FaultRules.decide(SofarSamples.reading(), List.of(), NOW);

        assertThat(decision.started()).isEmpty();
        assertThat(decision.startedText()).isNull();
        assertThat(decision.ended()).isEmpty();
        assertThat(decision.endedText()).isNull();
    }

    @Test
    void should_open_every_new_fault_with_one_alert() {
        final FaultRules.Decision decision = FaultRules.decide(SofarSamples.withFaults(List.of("NoGrid", "GridOVP")),
            List.of(), NOW);

        assertThat(decision.started()).containsExactly(
            new SourceAlertEntity(null, "sofar", "alarm", SofarSamples.COLLECTED_AT, NOW, null, "GridOVP"),
            new SourceAlertEntity(null, "sofar", "alarm", SofarSamples.COLLECTED_AT, NOW, null, "NoGrid"));
        assertThat(decision.startedText()).isEqualTo("SOFAR fault: GridOVP; NoGrid - since 10:19.");
    }

    @Test
    void should_neither_repeat_nor_end_a_fault_still_there() {
        final FaultRules.Decision decision = FaultRules.decide(SofarSamples.withFaults(List.of("GridOVP")),
            List.of(held("GridOVP", LocalDateTime.of(2026, 11, 2, 9, 40))), NOW);

        assertThat(decision.started()).isEmpty();
        assertThat(decision.ended()).isEmpty();
    }

    @Test
    void should_close_a_fault_the_reading_no_longer_has_with_one_info() {
        final FaultRules.Decision decision = FaultRules.decide(SofarSamples.reading(),
            List.of(held("GridOVP", LocalDateTime.of(2026, 11, 2, 9, 40))), NOW);

        assertThat(decision.ended()).containsExactly(new SourceAlertEntity(7L, "sofar", "alarm",
            LocalDateTime.of(2026, 11, 2, 9, 40), LocalDateTime.of(2026, 11, 2, 9, 40), NOW, "GridOVP"));
        assertThat(decision.endedText()).isEqualTo("SOFAR fault over: GridOVP - from 09:40 to 10:22 (42 min).");
    }

    //the detail column holds 200 characters
    @Test
    void should_cut_a_fault_longer_than_its_column() {
        final String words = "x".repeat(250);

        final FaultRules.Decision first = FaultRules.decide(SofarSamples.withFaults(List.of(words)), List.of(), NOW);
        final FaultRules.Decision again = FaultRules.decide(SofarSamples.withFaults(List.of(words)),
            List.of(held("x".repeat(200), NOW)), NOW);

        assertThat(first.started().getFirst().detail()).hasSize(200);
        assertThat(again.started()).isEmpty();
        assertThat(again.ended()).isEmpty();
    }
}

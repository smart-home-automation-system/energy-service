package cloud.cholewa.energy.shedding;

import cloud.cholewa.energy.api.model.GridStateReply;
import cloud.cholewa.energy.database.LoadSheddingEntity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//the rules of the load shedding (HAS-523): on at once with the grid lost and the switch on, over after ten unbroken
//minutes of the grid back - counted anew after every loss - or at once with the switch off; a grid not known decides
//nothing
class SheddingRulesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 28, 12, 0);
    private static final Duration STABLE = Duration.ofMinutes(10);
    private static final GridStateReply LOST = new GridStateReply(GridStateReply.LOST, NOW.minusMinutes(1));
    private static final GridStateReply NOT_KNOWN = GridStateReply.notKnown();
    private static final SheddingState NONE = SheddingState.loaded(null);
    private static final LoadSheddingEntity OPEN = new LoadSheddingEntity(3L, NOW.minusHours(1), null, null);
    private static final SheddingState ACTIVE = SheddingState.loaded(OPEN);

    private static GridStateReply presentSince(final LocalDateTime since) {
        return new GridStateReply(GridStateReply.PRESENT, since);
    }

    @Test
    void should_start_at_once_with_the_grid_lost_and_the_switch_on() {
        final SheddingRules.Decision decision = SheddingRules.decide(NONE, true, LOST, NOW, STABLE);

        assertThat(decision.change()).isEqualTo(SheddingRules.Change.STARTED);
        assertThat(decision.row()).isEqualTo(LoadSheddingEntity.started(NOW));
        assertThat(decision.next().open()).isEqualTo(LoadSheddingEntity.started(NOW));
    }

    @Test
    void should_not_start_with_the_switch_off() {
        final SheddingRules.Decision decision = SheddingRules.decide(NONE, false, LOST, NOW, STABLE);

        assertThat(decision.change()).isEqualTo(SheddingRules.Change.NONE);
        assertThat(decision.row()).isNull();
        assertThat(decision.next()).isEqualTo(NONE);
    }

    //a silent SOLAX, a pod start before two readings agree: never a start
    @Test
    void should_never_start_on_a_grid_not_known_or_present() {
        assertThat(SheddingRules.decide(NONE, true, NOT_KNOWN, NOW, STABLE).change())
            .isEqualTo(SheddingRules.Change.NONE);
        assertThat(SheddingRules.decide(NONE, true, presentSince(NOW.minusHours(2)), NOW, STABLE).change())
            .isEqualTo(SheddingRules.Change.NONE);
    }

    @Test
    void should_count_the_grid_back_and_end_after_the_stable_time() {
        final SheddingRules.Decision counting = SheddingRules.decide(ACTIVE, true, presentSince(NOW.minusMinutes(9)),
            NOW, STABLE);
        assertThat(counting.change()).isEqualTo(SheddingRules.Change.NONE);
        assertThat(counting.next().gridBackSince()).isEqualTo(NOW.minusMinutes(9));
        assertThat(SheddingRules.endsAt(counting.next(), STABLE)).isEqualTo(NOW.plusMinutes(1));

        final SheddingRules.Decision over = SheddingRules.decide(counting.next(), true,
            presentSince(NOW.minusMinutes(10)), NOW, STABLE);
        assertThat(over.change()).isEqualTo(SheddingRules.Change.ENDED);
        assertThat(over.row()).isEqualTo(new LoadSheddingEntity(3L, NOW.minusHours(1), NOW, LoadSheddingEntity.BY_GRID));
        assertThat(over.next().open()).isNull();
        assertThat(over.next().gridBackSince()).isNull();
    }

    //a new loss while the ten minutes run: the count starts anew from the next return
    @Test
    void should_count_anew_after_a_new_loss() {
        final SheddingState counting = ACTIVE.gridBackSince(NOW.minusMinutes(8));

        final SheddingRules.Decision lostAgain = SheddingRules.decide(counting, true, LOST, NOW, STABLE);
        assertThat(lostAgain.change()).isEqualTo(SheddingRules.Change.NONE);
        assertThat(lostAgain.next().gridBackSince()).isNull();
        assertThat(SheddingRules.endsAt(lostAgain.next(), STABLE)).isNull();

        final SheddingRules.Decision backAgain = SheddingRules.decide(lostAgain.next(), true,
            presentSince(NOW.plusMinutes(1)), NOW.plusMinutes(5), STABLE);
        assertThat(backAgain.change()).isEqualTo(SheddingRules.Change.NONE);
        assertThat(SheddingRules.endsAt(backAgain.next(), STABLE)).isEqualTo(NOW.plusMinutes(11));
    }

    //the SOLAX silent while it lasts: neither back nor lost, the count of a return kept
    @Test
    void should_hold_on_a_grid_not_known() {
        final SheddingState counting = ACTIVE.gridBackSince(NOW.minusMinutes(20));

        final SheddingRules.Decision decision = SheddingRules.decide(counting, true, NOT_KNOWN, NOW, STABLE);

        assertThat(decision.change()).isEqualTo(SheddingRules.Change.NONE);
        assertThat(decision.next()).isEqualTo(counting);
    }

    //switching it off ends it as a return would, whatever the grid
    @Test
    void should_end_at_once_with_the_switch_off() {
        final SheddingRules.Decision decision = SheddingRules.decide(ACTIVE, false, LOST, NOW, STABLE);

        assertThat(decision.change()).isEqualTo(SheddingRules.Change.ENDED);
        assertThat(decision.row()).isEqualTo(new LoadSheddingEntity(3L, NOW.minusHours(1), NOW,
            LoadSheddingEntity.BY_SWITCH));
        assertThat(decision.next().open()).isNull();
    }
}

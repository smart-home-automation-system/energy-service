package cloud.cholewa.energy.outage;

import cloud.cholewa.energy.database.GridOutageEntity;
import cloud.cholewa.energy.database.MeterUptimeEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//what a start of the grid meter means (HAS-505)
class MeterRestartRulesTest {

    private static final LocalDateTime SEEN = LocalDateTime.of(2026, 11, 24, 6, 57, 10);
    private static final LocalDateTime BOOTED_BEFORE = LocalDateTime.of(2026, 11, 10, 3, 0);
    private static final MeterUptimeEntity STORED = new MeterUptimeEntity(1, SEEN, BOOTED_BEFORE);

    @Test
    void should_take_a_start_after_the_meter_was_seen_with_power_on_for_an_outage_between_the_two() {
        final LocalDateTime booted = SEEN.plusMinutes(1);

        assertThat(MeterRestartRules.judge(STORED, booted, 1))
            .isEqualTo(new MeterRestartRules.Verdict(MeterRestartRules.Kind.OUTAGE, SEEN, booted, 1));
    }

    //a firmware update or a reboot (3), a crash or a watchdog: the meter restarted, the grid stayed
    @Test
    void should_take_another_reset_reason_for_a_restart_of_the_meter() {
        assertThat(MeterRestartRules.judge(STORED, SEEN.plusMinutes(1), 3).kind())
            .isEqualTo(MeterRestartRules.Kind.RESTART);
    }

    @Test
    void should_take_a_start_without_a_reset_reason_for_not_conclusive() {
        assertThat(MeterRestartRules.judge(STORED, SEEN.plusMinutes(1), null).kind())
            .isEqualTo(MeterRestartRules.Kind.NOT_CONCLUSIVE);
    }

    //the start of every answer of a meter that runs on lies before the time it was last seen
    @Test
    void should_see_nothing_while_the_meter_runs_on_or_before_it_was_ever_seen() {
        assertThat(MeterRestartRules.judge(STORED, BOOTED_BEFORE.plusSeconds(1), 1)).isEqualTo(
            MeterRestartRules.Verdict.NONE);
        assertThat(MeterRestartRules.judge(STORED, SEEN, 1)).isEqualTo(MeterRestartRules.Verdict.NONE);
        assertThat(MeterRestartRules.judge(new MeterUptimeEntity(1, null, null), SEEN, 1))
            .isEqualTo(MeterRestartRules.Verdict.NONE);
        assertThat(MeterRestartRules.judge(null, SEEN, 1)).isEqualTo(MeterRestartRules.Verdict.NONE);
    }

    @Test
    void should_hold_an_outage_the_live_watch_has_open_or_did_not_store() {
        final LocalDateTime from = SEEN;
        final LocalDateTime to = SEEN.plusMinutes(5);
        final GridOutageEntity open = GridOutageEntity.opened(SEEN.minusMinutes(1), 40.0, SEEN);
        final GridOutageEntity closedBefore = open.closed(SEEN.minusSeconds(1), 39.0);

        assertThat(MeterRestartRules.heldLive(GridWatch.loaded(open), from, to)).isTrue();
        assertThat(MeterRestartRules.heldLive(GridWatch.loaded(null).withUnsaved(open.closed(to, 38.0)), from, to))
            .isTrue();
        assertThat(MeterRestartRules.heldLive(GridWatch.loaded(null).withUnsaved(closedBefore), from, to)).isFalse();
        assertThat(MeterRestartRules.heldLive(GridWatch.loaded(GridOutageEntity.opened(to.plusSeconds(1), 40.0, to)),
            from, to)).isFalse();
        assertThat(MeterRestartRules.heldLive(GridWatch.NOT_LOADED, from, to)).isFalse();
    }
}

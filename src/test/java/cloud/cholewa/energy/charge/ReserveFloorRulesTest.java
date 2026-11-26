package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.charge.ReserveFloorRules.Notice;
import cloud.cholewa.energy.charge.ReserveFloorRules.Outcome;
import cloud.cholewa.energy.charge.ReserveFloorRules.Seen;
import cloud.cholewa.energy.charge.ReserveFloorRules.View;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.solax.SolaxSettings;
import cloud.cholewa.energy.solax.WorkMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

//the rules of a battery reserve the inverter does not hold (HAS-518): what a pass sees, the count to the warning, the
//reminders and the return - and the words, pinned whole (the owner reads them on Discord)
class ReserveFloorRulesTest {

    private static final LocalDateTime NINE = LocalDateTime.of(2026, 11, 26, 9, 0);
    private static final ReserveFloorProperties PROPERTIES = new ReserveFloorProperties(Duration.ofMinutes(10),
        Duration.ofHours(1));
    private static final SolaxSettings LOW_FLOOR = new SolaxSettings(161, WorkMode.BACK_UP, 75, 80);
    private static final SolaxSettings HELD = new SolaxSettings(161, WorkMode.BACK_UP, 80, 80);
    private static final SolaxSettings FEED_IN = new SolaxSettings(164, WorkMode.FEED_IN_PRIORITY, 75, 80);
    private static final Seen WRONG = new Seen(View.WRONG, 80, LOW_FLOOR);

    private static SourceAlertEntity open(final LocalDateTime lastToldAt) {
        return new SourceAlertEntity(9L, "solax", "reserve", NINE, lastToldAt, null, null);
    }

    private static Seen see(final boolean inForce, final boolean settled, final SolaxSettings settings) {
        return ReserveFloorRules.see(inForce, 80, settled, Optional.ofNullable(settings));
    }

    @Test
    void should_see_a_min_soc_below_the_level_as_wrong_and_one_at_it_or_above_as_fine() {
        assertThat(see(true, true, LOW_FLOOR)).isEqualTo(WRONG);
        assertThat(see(true, true, HELD)).isEqualTo(new Seen(View.FINE, 80, HELD));
        assertThat(see(true, true, new SolaxSettings(161, WorkMode.BACK_UP, 85, 85)).view()).isEqualTo(View.FINE);
    }

    //the percentages of Back Up are stored whatever mode runs: out of Back Up no reserve is held, whatever they say
    @Test
    void should_see_any_work_mode_but_back_up_as_wrong() {
        assertThat(see(true, true, FEED_IN)).isEqualTo(new Seen(View.WRONG, 80, FEED_IN));
        assertThat(see(true, true, new SolaxSettings(160, WorkMode.SELF_USE, 80, 80)).view()).isEqualTo(View.WRONG);
        assertThat(see(true, true, new SolaxSettings(163, WorkMode.OTHER, 80, 80)).view()).isEqualTo(View.WRONG);
    }

    @Test
    void should_not_judge_what_is_not_known_or_not_written_yet() {
        assertThat(see(true, true, null).view()).isEqualTo(View.NOT_JUDGED);
        assertThat(see(true, false, LOW_FLOOR).view()).isEqualTo(View.NOT_JUDGED);
        assertThat(see(true, true, new SolaxSettings(161, WorkMode.BACK_UP, null, 80)).view())
            .isEqualTo(View.NOT_JUDGED);
    }

    @Test
    void should_see_no_reserve_as_off_whatever_the_inverter_says() {
        assertThat(see(false, true, LOW_FLOOR).view()).isEqualTo(View.OFF);
        assertThat(see(false, false, null).view()).isEqualTo(View.OFF);
    }

    @Test
    void should_answer_the_floor_below_the_level_only_for_a_reserve_that_holds_and_a_known_min_soc() {
        assertThat(ReserveFloorRules.floorBelowLevel(true, 80, Optional.of(LOW_FLOOR))).isTrue();
        assertThat(ReserveFloorRules.floorBelowLevel(true, 80, Optional.of(HELD))).isFalse();
        assertThat(ReserveFloorRules.floorBelowLevel(true, 80, Optional.empty())).isNull();
        assertThat(ReserveFloorRules.floorBelowLevel(true, 80,
            Optional.of(new SolaxSettings(161, WorkMode.BACK_UP, null, 80)))).isNull();
        assertThat(ReserveFloorRules.floorBelowLevel(false, 80, Optional.of(LOW_FLOOR))).isFalse();
        assertThat(ReserveFloorRules.floorBelowLevel(false, 80, Optional.empty())).isFalse();
    }

    @Test
    void should_count_from_the_first_wrong_pass_and_stay_quiet_before_ten_minutes() {
        final Outcome first = ReserveFloorRules.decide(WRONG, Optional.empty(), null, PROPERTIES, NINE);
        final Outcome later = ReserveFloorRules.decide(WRONG, Optional.empty(), NINE, PROPERTIES,
            NINE.plusMinutes(9));

        assertThat(first).isEqualTo(Outcome.quiet(NINE));
        assertThat(later).isEqualTo(Outcome.quiet(NINE));
    }

    @Test
    void should_warn_of_a_low_floor_after_ten_minutes_naming_both_numbers_and_what_to_do() {
        final Outcome outcome = ReserveFloorRules.decide(WRONG, Optional.empty(), NINE, PROPERTIES,
            NINE.plusMinutes(10));

        assertThat(outcome.notice()).contains(Notice.WARNING);
        assertThat(outcome.row()).contains(new SourceAlertEntity(null, "solax", "reserve", NINE,
            NINE.plusMinutes(10), null, null));
        assertThat(outcome.text()).isEqualTo("The battery reserve of 80 % is held only down to 75 %: the Min SoC of the "
            + "Back Up mode of the SOLAX is 75 % since 09:00. The inverter never raises it by itself and the service "
            + "cannot: set Min SoC to 80 % by hand in the SolaX application or SolaxCloud.");
    }

    @Test
    void should_warn_of_another_work_mode_with_its_name_and_code() {
        final Outcome outcome = ReserveFloorRules.decide(new Seen(View.WRONG, 80, FEED_IN), Optional.empty(), NINE,
            PROPERTIES, NINE.plusMinutes(10));

        assertThat(outcome.text()).isEqualTo("The battery reserve of 80 % is not held: the work mode of the SOLAX is "
            + "feed-in priority (code 164), not Back Up since 09:00. Set the work mode back to Back Up in the SolaX "
            + "application or SolaxCloud, or switch the reserve off and on in the dashboard.");
    }

    @Test
    void should_start_the_count_anew_after_a_pass_that_is_fine_or_not_judged() {
        assertThat(ReserveFloorRules.decide(new Seen(View.FINE, 80, HELD), Optional.empty(), NINE, PROPERTIES,
            NINE.plusMinutes(5))).isEqualTo(Outcome.quiet(null));
        assertThat(ReserveFloorRules.decide(Seen.of(View.NOT_JUDGED), Optional.empty(), NINE, PROPERTIES,
            NINE.plusMinutes(5))).isEqualTo(Outcome.quiet(null));
    }

    @Test
    void should_remind_an_hour_after_the_last_notice_and_not_before() {
        final Outcome early = ReserveFloorRules.decide(WRONG, Optional.of(open(NINE.plusMinutes(10))), null,
            PROPERTIES, NINE.plusMinutes(69));
        final Outcome due = ReserveFloorRules.decide(WRONG, Optional.of(open(NINE.plusMinutes(10))), null,
            PROPERTIES, NINE.plusMinutes(70));

        assertThat(early).isEqualTo(Outcome.quiet(null));
        assertThat(due.notice()).contains(Notice.REMINDER);
        assertThat(due.row()).contains(new SourceAlertEntity(9L, "solax", "reserve", NINE, NINE.plusMinutes(70), null,
            null));
        assertThat(due.text()).startsWith("Still: The battery reserve of 80 % is held only down to 75 %")
            .contains("since 09:00.").endsWith("by hand in the SolaX application or SolaxCloud.");
    }

    //an episode whose warning never got through (the table follows the broker, so this is a row of a restart) is told
    //with the next wrong pass
    @Test
    void should_tell_an_open_episode_never_told_at_once() {
        assertThat(ReserveFloorRules.decide(WRONG, Optional.of(open(null)), null, PROPERTIES, NINE.plusMinutes(1))
            .notice()).contains(Notice.REMINDER);
    }

    @Test
    void should_close_the_episode_once_the_floor_is_at_the_level() {
        final Outcome outcome = ReserveFloorRules.decide(new Seen(View.FINE, 80, HELD),
            Optional.of(open(NINE.plusMinutes(10))), null, PROPERTIES, NINE.plusMinutes(72));

        assertThat(outcome.notice()).contains(Notice.INFO);
        assertThat(outcome.row()).contains(new SourceAlertEntity(9L, "solax", "reserve", NINE, NINE.plusMinutes(10),
            NINE.plusMinutes(72), null));
        assertThat(outcome.text()).isEqualTo("The SOLAX holds the battery reserve of 80 % again: Back Up with Min SoC "
            + "80 % - not held from 09:00 to 10:12 (1 h 12 min).");
    }

    @Test
    void should_close_the_episode_when_the_reserve_is_switched_off() {
        final Outcome outcome = ReserveFloorRules.decide(Seen.of(View.OFF), Optional.of(open(NINE.plusMinutes(10))),
            null, PROPERTIES, NINE.plusMinutes(30));

        assertThat(outcome.notice()).contains(Notice.INFO);
        assertThat(outcome.row().map(SourceAlertEntity::closedAt)).contains(NINE.plusMinutes(30));
        assertThat(outcome.text()).isEqualTo(
            "The battery reserve is switched off - the warning of its floor, open since 09:00, ends.");
    }

    //settings not known, a charge going on, a write pending: the episode neither ends nor is it reminded
    @Test
    void should_leave_an_open_episode_alone_while_nothing_is_judged() {
        assertThat(ReserveFloorRules.decide(Seen.of(View.NOT_JUDGED), Optional.of(open(NINE)), null, PROPERTIES,
            NINE.plusHours(3))).isEqualTo(Outcome.quiet(null));
    }

    @Test
    void should_say_nothing_without_an_episode_when_off_or_fine() {
        assertThat(ReserveFloorRules.decide(Seen.of(View.OFF), Optional.empty(), NINE, PROPERTIES, NINE))
            .isEqualTo(Outcome.quiet(null));
        assertThat(ReserveFloorRules.decide(new Seen(View.FINE, 80, HELD), Optional.empty(), null, PROPERTIES, NINE))
            .isEqualTo(Outcome.quiet(null));
    }
}

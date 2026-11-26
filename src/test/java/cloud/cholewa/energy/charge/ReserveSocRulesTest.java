package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.database.SourceAlertEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static cloud.cholewa.energy.charge.ChargeSamples.NOW;
import static org.assertj.core.api.Assertions.assertThat;

//the rule of a battery below its reserve (HAS-495) without I/O: what a pass sees, held of the API and the words of the
//notices, pinned whole
class ReserveSocRulesTest {

    private static final ReserveSocProperties PROPERTIES = ChargeSamples.SOC;

    private static ReserveSocRules.Seen see(final boolean inForce, final boolean judged, final Double soc,
                                            final boolean charging) {
        return ReserveSocRules.see(inForce, 80, judged, Optional.ofNullable(soc), charging, 5);
    }

    @Test
    void should_see_off_without_a_reserve_whatever_the_battery() {
        assertThat(see(false, true, 20.0, false).view()).isEqualTo(ReserveSocRules.View.OFF);
    }

    @Test
    void should_not_judge_an_outage_a_charge_a_write_not_taken_or_a_battery_not_known() {
        assertThat(see(true, false, 20.0, false).view()).isEqualTo(ReserveSocRules.View.NOT_JUDGED);
        assertThat(see(true, true, null, false).view()).isEqualTo(ReserveSocRules.View.NOT_JUDGED);
    }

    //75 % is the level less the tolerance - still held; below it only while not charging
    @ParameterizedTest
    @CsvSource({"80.0, false, FINE", "75.0, false, FINE", "74.9, false, LOW", "40.0, true, NOT_JUDGED",
        "95.0, true, FINE"})
    void should_judge_the_battery_against_the_level_less_the_tolerance(final double soc, final boolean charging,
                                                                       final ReserveSocRules.View view) {
        assertThat(see(true, true, soc, charging).view()).isEqualTo(view);
    }

    @Test
    void should_answer_held_only_with_a_reserve_in_force_and_a_known_battery() {
        assertThat(ReserveSocRules.held(true, 80, Optional.of(75.0), 5)).isTrue();
        assertThat(ReserveSocRules.held(true, 80, Optional.of(74.0), 5)).isFalse();
        assertThat(ReserveSocRules.held(true, 80, Optional.empty(), 5)).isNull();
        assertThat(ReserveSocRules.held(false, 80, Optional.of(20.0), 5)).isNull();
    }

    @Test
    void should_stay_quiet_below_the_reserve_until_warn_after() {
        final ReserveSocRules.Outcome outcome = ReserveSocRules.decide(see(true, true, 62.0, false), Optional.empty(),
            NOW.minusMinutes(29), PROPERTIES, NOW);

        assertThat(outcome).isEqualTo(ReserveSocRules.Outcome.quiet(NOW.minusMinutes(29)));
    }

    @Test
    void should_warn_after_half_an_hour_below_the_reserve_in_these_words() {
        final ReserveSocRules.Outcome outcome = ReserveSocRules.decide(see(true, true, 61.6, false), Optional.empty(),
            NOW.minusMinutes(30), PROPERTIES, NOW);

        assertThat(outcome.notice()).contains(ReserveSocRules.Notice.WARNING);
        assertThat(outcome.row()).contains(new SourceAlertEntity(null, "battery", "reserve", NOW.minusMinutes(30), NOW,
            null, null));
        assertThat(outcome.text()).isEqualTo("The battery reserve of 80 % is not held: the battery is at 62 % and not "
            + "charging, below the reserve since 13:35. Check the work mode and the Min SoC of the SOLAX in the SolaX "
            + "application or SolaxCloud.");
    }

    @Test
    void should_remind_an_hour_after_the_last_notice() {
        final SourceAlertEntity open = new SourceAlertEntity(4L, "battery", "reserve", NOW.minusHours(2),
            NOW.minusHours(1), null, null);

        final ReserveSocRules.Outcome outcome = ReserveSocRules.decide(see(true, true, 60.0, false), Optional.of(open),
            null, PROPERTIES, NOW);

        assertThat(outcome.notice()).contains(ReserveSocRules.Notice.REMINDER);
        assertThat(outcome.text()).startsWith("Still: The battery reserve of 80 % is not held: the battery is at 60 % "
            + "and not charging, below the reserve since 12:05.");
        assertThat(ReserveSocRules.decide(see(true, true, 60.0, false), Optional.of(open), null, PROPERTIES,
            NOW.minusMinutes(1))).isEqualTo(ReserveSocRules.Outcome.quiet(null));
    }

    @Test
    void should_tell_the_return_and_a_reserve_switched_off_in_these_words() {
        final SourceAlertEntity open = new SourceAlertEntity(4L, "battery", "reserve", NOW.minusMinutes(65),
            NOW.minusMinutes(35), null, null);

        final ReserveSocRules.Outcome back = ReserveSocRules.decide(see(true, true, 78.2, false), Optional.of(open),
            null, PROPERTIES, NOW);
        final ReserveSocRules.Outcome off = ReserveSocRules.decide(see(false, true, 40.0, false), Optional.of(open),
            null, PROPERTIES, NOW);

        assertThat(back.notice()).contains(ReserveSocRules.Notice.INFO);
        assertThat(back.row().map(SourceAlertEntity::closedAt)).contains(NOW);
        assertThat(back.text()).isEqualTo("The battery is held at the reserve of 80 % again: 78 % - below it from "
            + "13:00 to 14:05 (1 h 5 min).");
        assertThat(off.text()).isEqualTo("The battery reserve is switched off - the warning of the battery below it, "
            + "open since 13:00, ends.");
    }

    //charging again below the reserve keeps the episode open and quiet - it ends only once the battery is back
    @Test
    void should_neither_remind_nor_close_while_the_battery_charges_below_the_reserve() {
        final SourceAlertEntity open = new SourceAlertEntity(4L, "battery", "reserve", NOW.minusHours(3),
            NOW.minusHours(2), null, null);

        assertThat(ReserveSocRules.decide(see(true, true, 50.0, true), Optional.of(open), null, PROPERTIES, NOW))
            .isEqualTo(ReserveSocRules.Outcome.quiet(null));
    }
}

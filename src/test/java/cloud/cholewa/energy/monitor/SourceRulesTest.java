package cloud.cholewa.energy.monitor;

import cloud.cholewa.energy.database.SourceAlertEntity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//what the monitor of the sources decides (HAS-332): silence by the pattern of blinds and light, the overload of a phase
//once an episode; the opening held at once, what a notice changes only once it was published (applied here as the
//monitor does)
class SourceRulesTest {

    static final AvailabilityProperties AVAILABILITY = new AvailabilityProperties(Duration.ofMinutes(5),
        Duration.ofMinutes(5), Duration.ofHours(1));
    static final OverloadProperties OVERLOAD = new OverloadProperties(22.0, Duration.ofMinutes(3));
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 1, 18, 0);

    private SourceWatch held = SourceWatch.loaded(Map.of());

    private SourceRules.Decision pass(final LocalDateTime now, final Map<Source, Silence> silences,
                                      final Map<Source, Double> currents) {
        final SourceRules.Decision decision = SourceRules.decide(held, silences, currents, now, AVAILABILITY, OVERLOAD);
        held = decision.next();
        return decision;
    }

    //the notices published: what they change is held
    private SourceRules.Decision published(final SourceRules.Decision decision) {
        for (final SourceRules.Notice notice : decision.notices()) {
            for (final SourceRules.Row row : notice.rows()) {
                held = held.with(row.entity(), row.source());
            }
        }
        return decision;
    }

    private static Map<Source, Silence> solax(final Silence silence) {
        final Map<Source, Silence> silences = new EnumMap<>(Source.class);
        silences.put(Source.SOLAX, silence);
        return silences;
    }

    private static Map<Source, Double> phaseB(final double current) {
        return Map.of(Source.PHASE_A, 10.0, Source.PHASE_B, current, Source.PHASE_C, 10.0);
    }

    private static List<SourceRules.Type> types(final SourceRules.Decision decision) {
        return decision.notices().stream().map(SourceRules.Notice::type).toList();
    }

    @Test
    void should_alert_once_remind_hourly_and_tell_the_return_of_a_silent_source() {
        final LocalDateTime heard = T0.minusMinutes(5);

        assertThat(types(published(pass(T0, solax(Silence.out(heard)), Map.of()))))
            .containsExactly(SourceRules.Type.ALERT);
        assertThat(held.open().get(Source.SOLAX).openedAt()).isEqualTo(heard);
        assertThat(types(published(pass(T0.plusMinutes(1), solax(Silence.out(heard)), Map.of())))).isEmpty();
        assertThat(types(published(pass(T0.plusMinutes(59), solax(Silence.out(heard)), Map.of())))).isEmpty();
        assertThat(types(published(pass(T0.plusMinutes(60), solax(Silence.out(heard)), Map.of()))))
            .containsExactly(SourceRules.Type.REMINDER);

        final SourceRules.Decision back = published(pass(T0.plusMinutes(70),
            solax(Silence.heard(T0.plusMinutes(69))), Map.of()));

        assertThat(types(back)).containsExactly(SourceRules.Type.INFO);
        assertThat(back.notices().getFirst().text())
            .isEqualTo("Available again: the SOLAX dongle - out from 17:55 to 19:09 (1 h 14 min).");
        assertThat(held.open()).isEmpty();
        assertThat(held.unsaved()).singleElement().satisfies(row -> assertThat(row.closedAt()).isEqualTo(
            T0.plusMinutes(69)));
    }

    //one alert a pass naming every silent source
    @Test
    void should_name_every_silent_source_in_one_alert() {
        final SourceRules.Decision decision = pass(T0, Map.of(Source.SOLAX, Silence.out(T0.minusMinutes(6)),
            Source.METER, Silence.out(T0.minusMinutes(8))), Map.of());

        assertThat(decision.notices()).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(SourceRules.Type.ALERT);
            assertThat(notice.rows()).hasSize(2);
            assertThat(notice.text()).contains("the SOLAX dongle does not answer since 17:54 (6 min)")
                .contains("the grid meter is silent since 17:52 (8 min)");
        });
    }

    //an alert that did not get through is tried again with the next pass, and the return names it
    @Test
    void should_try_an_alert_again_and_name_one_that_never_got_through() {
        final LocalDateTime heard = T0.minusMinutes(5);
        pass(T0, solax(Silence.out(heard)), Map.of());

        assertThat(types(pass(T0.plusMinutes(1), solax(Silence.out(heard)), Map.of())))
            .containsExactly(SourceRules.Type.ALERT);
        assertThat(pass(T0.plusMinutes(2), solax(Silence.heard(T0.plusMinutes(2))), Map.of()).notices().getFirst()
            .text()).endsWith("(7 min), the alert did not get through.");
    }

    //a source only not known, or heard before the episode opened, is not back (HAS-296); not judged, no alert
    @Test
    void should_never_take_a_source_not_known_for_a_return() {
        final LocalDateTime heard = T0.minusMinutes(5);
        published(pass(T0, solax(Silence.out(heard)), Map.of()));

        assertThat(pass(T0.plusMinutes(1), solax(Silence.NOT_KNOWN), Map.of()).notices()).isEmpty();
        assertThat(pass(T0.plusMinutes(2), solax(Silence.heard(heard)), Map.of()).notices()).isEmpty();
        assertThat(held.open()).containsKey(Source.SOLAX);
        assertThat(new SourceRulesTest().pass(T0, solax(Silence.NOT_KNOWN), Map.of()).notices()).isEmpty();
    }

    //an episode read from the database after a restart: the next reminder an hour after the last one, never at once
    @Test
    void should_remind_an_episode_held_over_a_restart_an_hour_after_the_last_notice() {
        held = SourceWatch.loaded(Map.of(Source.METER, new SourceAlertEntity(4L, "meter", "silent",
            T0.minusHours(2), T0.minusMinutes(30), null, null)));

        assertThat(pass(T0, Map.of(Source.METER, Silence.out(T0.minusHours(2))), Map.of()).notices()).isEmpty();
        assertThat(types(pass(T0.plusMinutes(30), Map.of(Source.METER, Silence.out(T0.minusHours(2))), Map.of())))
            .containsExactly(SourceRules.Type.REMINDER);
    }

    @Test
    void should_warn_of_a_phase_above_the_current_for_three_minutes_once() {
        assertThat(pass(T0, Map.of(), phaseB(23.4)).notices()).isEmpty();
        assertThat(pass(T0.plusMinutes(2), Map.of(), phaseB(23.0)).notices()).isEmpty();

        final SourceRules.Decision warned = published(pass(T0.plusMinutes(3), Map.of(), phaseB(24.1)));

        assertThat(warned.notices()).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(SourceRules.Type.OVERLOAD);
            assertThat(notice.text())
                .isEqualTo("Phase B overloaded since 18:00 (3 min): 24.1 A, above the warning level of 22 A.");
        });
        assertThat(pass(T0.plusMinutes(30), Map.of(), phaseB(24.1)).notices()).isEmpty();
    }

    //a pass below, or a reading not known, starts the count anew
    @Test
    void should_count_the_overload_anew_after_a_pass_below_or_not_known() {
        pass(T0, Map.of(), phaseB(23.0));
        pass(T0.plusMinutes(1), Map.of(), phaseB(22.0));
        pass(T0.plusMinutes(2), Map.of(), phaseB(23.0));
        pass(T0.plusMinutes(3), Map.of(), Map.of());
        pass(T0.plusMinutes(4), Map.of(), phaseB(23.0));

        assertThat(pass(T0.plusMinutes(6), Map.of(), phaseB(23.0)).notices()).isEmpty();
        assertThat(types(pass(T0.plusMinutes(7), Map.of(), phaseB(23.0)))).containsExactly(SourceRules.Type.OVERLOAD);
    }

    @Test
    void should_end_an_overload_below_the_current_for_three_minutes() {
        pass(T0, Map.of(), phaseB(23.0));
        published(pass(T0.plusMinutes(3), Map.of(), phaseB(23.0)));
        pass(T0.plusMinutes(10), Map.of(), phaseB(18.0));
        pass(T0.plusMinutes(11), Map.of(), phaseB(23.0));
        pass(T0.plusMinutes(12), Map.of(), phaseB(18.0));
        assertThat(pass(T0.plusMinutes(14), Map.of(), phaseB(18.0)).notices()).isEmpty();

        final SourceRules.Decision over = published(pass(T0.plusMinutes(15), Map.of(), phaseB(18.2)));

        assertThat(over.notices()).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(SourceRules.Type.OVERLOAD_END);
            assertThat(notice.text())
                .isEqualTo("Phase B below 22 A again: 18.2 A - overloaded from 18:00 to 18:12 (12 min).");
        });
        assertThat(held.open()).isEmpty();
        assertThat(pass(T0.plusMinutes(16), Map.of(), phaseB(18.0)).notices()).isEmpty();
    }

    //a warning that did not get through is tried again while the phase stays above
    @Test
    void should_try_a_warning_again_while_the_phase_stays_above() {
        pass(T0, Map.of(), phaseB(23.0));
        pass(T0.plusMinutes(3), Map.of(), phaseB(23.0));

        assertThat(types(pass(T0.plusMinutes(4), Map.of(), phaseB(23.0)))).containsExactly(SourceRules.Type.OVERLOAD);
    }
}

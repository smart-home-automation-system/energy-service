package cloud.cholewa.energy.frequency;

import cloud.cholewa.energy.database.SourceAlertEntity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

//what the grid frequency is to the household (HAS-503): the grid meter decides after off-after, back after back-after,
//a reminder an hour, a frequency not known never decides, the SOLAX only in the words; the words pinned whole
class FrequencyRulesTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 24, 7, 5);
    private static final FrequencyProperties PROPERTIES = new FrequencyProperties(49.8, 50.2, Duration.ofMinutes(2),
        Duration.ofMinutes(5), Duration.ofHours(1));
    private static final String CAUSE = "the house is likely fed by a generator of Tauron: a planned outage in the town "
        + "Tue 24 Nov 06:00-18:00";

    private static FrequencyRules.Reading at(final Double hz) {
        return new FrequencyRules.Reading(hz, hz, hz, 231.5, 227.0, 229.6, 50.99);
    }

    private static FrequencyRules.Outcome decide(final FrequencyRules.Reading reading,
                                                 final Optional<SourceAlertEntity> open,
                                                 final FrequencyRules.Memory memory, final LocalDateTime now) {
        return FrequencyRules.decide(reading, open, memory, CAUSE, PROPERTIES, now);
    }

    private static SourceAlertEntity open(final LocalDateTime toldAt) {
        return new SourceAlertEntity(7L, "meter", "frequency", T0, toldAt, null, null);
    }

    @Test
    void should_take_the_phase_furthest_from_fifty_hertz() {
        assertThat(new FrequencyRules.Reading(50.0, 51.02, 49.9, null, null, null, null).hz()).isEqualTo(51.02);
        assertThat(new FrequencyRules.Reading(null, 49.5, null, null, null, null, null).hz()).isEqualTo(49.5);
        assertThat(new FrequencyRules.Reading(null, null, null, null, null, null, 51.0).hz()).isNull();
    }

    @Test
    void should_count_an_off_frequency_and_alert_after_off_after() {
        final FrequencyRules.Outcome first = decide(at(51.0), Optional.empty(), FrequencyRules.Memory.EMPTY, T0);
        assertThat(first.notice()).isEmpty();
        assertThat(first.memory().offSince()).isEqualTo(T0);

        final FrequencyRules.Outcome later = decide(at(51.03), Optional.empty(), first.memory(), T0.plusSeconds(90));
        assertThat(later.notice()).isEmpty();

        final FrequencyRules.Outcome alert = decide(at(51.01), Optional.empty(), later.memory(), T0.plusMinutes(2));
        assertThat(alert.notice()).contains(FrequencyRules.Notice.ALERT);
        assertThat(alert.row()).contains(new SourceAlertEntity(null, "meter", "frequency", T0, T0.plusMinutes(2),
            null, null));
        assertThat(alert.text()).isEqualTo("The grid frequency is 51.01 Hz since 07:05 (49.80 - 50.20 Hz allowed; the "
            + "voltages 232 / 227 / 230 V; the SOLAX measures 50.99 Hz) - the house is likely fed by a generator of "
            + "Tauron: a planned outage in the town Tue 24 Nov 06:00-18:00. The inverters do not connect to such a grid: "
            + "no production of the panels, no charge of the battery from the grid.");
    }

    @Test
    void should_count_anew_after_a_pass_inside_the_band() {
        final FrequencyRules.Outcome off = decide(at(49.6), Optional.empty(), FrequencyRules.Memory.EMPTY, T0);
        final FrequencyRules.Outcome inside = decide(at(50.01), Optional.empty(), off.memory(), T0.plusMinutes(1));
        assertThat(inside.memory()).isEqualTo(FrequencyRules.Memory.EMPTY);

        final FrequencyRules.Outcome again = decide(at(49.6), Optional.empty(), inside.memory(), T0.plusMinutes(2));
        assertThat(again.notice()).isEmpty();
        assertThat(again.memory().offSince()).isEqualTo(T0.plusMinutes(2));
    }

    @Test
    void should_hold_the_band_edges_as_inside() {
        assertThat(decide(at(50.2), Optional.empty(), FrequencyRules.Memory.EMPTY, T0).memory())
            .isEqualTo(FrequencyRules.Memory.EMPTY);
        assertThat(decide(at(49.8), Optional.empty(), FrequencyRules.Memory.EMPTY, T0).memory())
            .isEqualTo(FrequencyRules.Memory.EMPTY);
    }

    @Test
    void should_never_decide_on_a_frequency_not_known() {
        final FrequencyRules.Memory counting = new FrequencyRules.Memory(T0, null, 51.0, 51.0);
        assertThat(decide(at(null), Optional.empty(), counting, T0.plusMinutes(5)))
            .isEqualTo(FrequencyRules.Outcome.quiet(FrequencyRules.Memory.EMPTY));

        final FrequencyRules.Memory coming = new FrequencyRules.Memory(null, T0, 50.9, 51.1);
        final FrequencyRules.Outcome withOpen = decide(at(null), Optional.of(open(T0)), coming, T0.plusMinutes(10));
        assertThat(withOpen.notice()).isEmpty();
        assertThat(withOpen.memory()).isEqualTo(new FrequencyRules.Memory(null, null, 50.9, 51.1));
    }

    @Test
    void should_remind_every_hour_while_off() {
        final FrequencyRules.Memory memory = new FrequencyRules.Memory(T0, null, 50.98, 51.02);
        assertThat(decide(at(51.0), Optional.of(open(T0)), memory, T0.plusMinutes(59)).notice()).isEmpty();

        final FrequencyRules.Outcome reminder = decide(at(51.04), Optional.of(open(T0)), memory, T0.plusHours(1));
        assertThat(reminder.notice()).contains(FrequencyRules.Notice.REMINDER);
        assertThat(reminder.row()).contains(new SourceAlertEntity(7L, "meter", "frequency", T0, T0.plusHours(1), null,
            null));
        assertThat(reminder.text()).isEqualTo("The grid frequency is still off: 51.04 Hz since 07:05 (50.98 - 51.04 Hz "
            + "seen; the voltages 232 / 227 / 230 V) - " + CAUSE + ".");
    }

    @Test
    void should_end_an_episode_after_back_after_inside_the_band() {
        final FrequencyRules.Memory memory = new FrequencyRules.Memory(null, null, 50.97, 51.03);
        final FrequencyRules.Outcome first = decide(at(50.01), Optional.of(open(T0)), memory, T0.plusHours(11));
        assertThat(first.notice()).isEmpty();
        assertThat(first.memory().backSince()).isEqualTo(T0.plusHours(11));

        final FrequencyRules.Outcome info = decide(at(49.99), Optional.of(open(T0)), first.memory(),
            T0.plusHours(11).plusMinutes(5));
        assertThat(info.notice()).contains(FrequencyRules.Notice.INFO);
        assertThat(info.row()).contains(new SourceAlertEntity(7L, "meter", "frequency", T0, T0, T0.plusHours(11),
            null));
        assertThat(info.memory()).isEqualTo(FrequencyRules.Memory.EMPTY);
        assertThat(info.text()).isEqualTo("The grid frequency is back: 49.99 Hz - off from 07:05 to 18:05 (11 h 0 min), "
            + "50.97 - 51.03 Hz seen.");
    }

    @Test
    void should_wait_again_when_it_goes_off_before_back_after() {
        final FrequencyRules.Memory coming = new FrequencyRules.Memory(null, T0.plusHours(1), 51.0, 51.0);
        final FrequencyRules.Outcome off = decide(at(51.0), Optional.of(open(T0.plusHours(1))), coming,
            T0.plusHours(1).plusMinutes(3));
        assertThat(off.notice()).isEmpty();
        assertThat(off.memory().backSince()).isNull();
    }

    @Test
    void should_tell_the_range_as_not_known_after_a_restart() {
        final FrequencyRules.Memory empty = new FrequencyRules.Memory(null, T0.plusHours(2), null, null);
        final FrequencyRules.Outcome info = decide(at(50.0), Optional.of(open(T0)), empty,
            T0.plusHours(2).plusMinutes(5));
        assertThat(info.text()).endsWith("(2 h 0 min), the range not known.");
    }
}

package cloud.cholewa.energy.outage;

import cloud.cholewa.energy.database.GridOutageEntity;
import cloud.cholewa.energy.outage.OutageRules.Decision;
import cloud.cholewa.energy.outage.OutageRules.Kind;
import cloud.cholewa.energy.outage.OutageRules.Notice;
import cloud.cholewa.energy.solax.SolaxReading;
import cloud.cholewa.energy.solax.SolaxState;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static cloud.cholewa.energy.outage.OutageSamples.CAPACITY;
import static cloud.cholewa.energy.outage.OutageSamples.PROPERTIES;
import static cloud.cholewa.energy.outage.OutageSamples.eps;
import static cloud.cholewa.energy.outage.OutageSamples.present;
import static org.assertj.core.api.Assertions.assertThat;

//the rules of an outage of the grid without I/O (HAS-334)
class OutageRulesTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 31, 19, 42);

    private GridWatch watch = GridWatch.loaded(null);

    //one reading of the SOLAX 15 s after the previous one, decided at the time it came; the held state moves on as if
    //the row was stored
    private Decision read(final int step, final SolaxReading reading) {
        final LocalDateTime at = T0.plusSeconds(15L * step);
        return at(at, new SolaxState(134L, reading, at, true));
    }

    private Decision at(final LocalDateTime now, final SolaxState solax) {
        final Decision decision = OutageRules.decide(watch, solax, now, PROPERTIES, CAPACITY);
        final GridOutageEntity open = decision.next().open();
        watch = decision.next().settled();
        return decision;
    }

    @Test
    void should_not_open_an_outage_on_one_reading_of_eps() {
        final Decision decision = read(0, eps(64, -2100));

        assertThat(decision.row()).isNull();
        assertThat(decision.notices()).isEmpty();
        assertThat(decision.next().open()).isNull();
    }

    //two in a row: the outage starts at the first of them, an alert by SMS with the battery and the time left
    @Test
    void should_open_an_outage_on_the_second_reading_of_eps() {
        read(0, eps(64, -2100));
        final Decision decision = read(1, eps(64, -2100));

        assertThat(decision.row()).isEqualTo(new GridOutageEntity(null, T0, null, 64.0, null, 64.0,
            T0.plusSeconds(15), null));
        assertThat(decision.notices()).containsExactly(new Notice(Kind.ALERT_SMS,
            "Grid lost at 19:42 - the house runs on the battery: 64 %, about 6 h 49 min left at 2.1 kW."));
    }

    //lost with the battery low already: the alert of the loss tells it, no second SMS for the low battery
    @Test
    void should_not_alert_a_low_battery_again_when_it_was_low_at_the_loss() {
        read(0, eps(15, -2000));
        final Decision opened = read(1, eps(15, -2000));
        final Decision later = read(2, eps(14, -2000));

        assertThat(opened.notices()).extracting(Notice::kind).containsExactly(Kind.ALERT_SMS);
        assertThat(opened.row().lowSocAlertedAt()).isEqualTo(T0.plusSeconds(15));
        assertThat(later.notices()).isEmpty();
    }

    //a reading that does not change counts once, whatever the passes
    @Test
    void should_count_one_reading_once() {
        read(0, eps(64, -2100));

        final Decision decision = at(T0.plusSeconds(15), new SolaxState(134L, eps(64, -2100), T0, true));

        assertThat(decision.row()).isNull();
        assertThat(decision.next().streak()).isEqualTo(1);
    }

    //a phase back, or a voltage not known, breaks the streak: the loss needs two readings that say so in a row
    @Test
    void should_start_again_when_a_reading_says_neither() {
        read(0, eps(64, -2100));
        read(1, OutageSamples.reading(2, 230.0, 0.0, 230.0, 64.0, -2100.0));
        final Decision decision = read(2, eps(64, -2100));

        assertThat(decision.row()).isNull();
        assertThat(decision.next().streakSince()).isEqualTo(T0.plusSeconds(30));
    }

    @Test
    void should_keep_the_lowest_state_of_charge_while_it_lasts() {
        read(0, eps(64, -2100));
        read(1, eps(64, -2100));
        read(2, eps(61, -2100));
        final Decision decision = read(3, eps(62, -2100));

        assertThat(decision.row()).isNull();
        assertThat(decision.next().open().lowestSoc()).isEqualTo(61.0);
    }

    //below energy.outage.low-soc one alert more by SMS, once an outage
    @Test
    void should_alert_a_low_battery_once() {
        read(0, eps(22, -2000));
        read(1, eps(22, -2000));
        final Decision low = read(2, eps(19, -2000));
        final Decision lower = read(3, eps(18, -2000));

        assertThat(low.notices()).containsExactly(new Notice(Kind.ALERT_SMS,
            "Battery low, the grid lost since 19:42: 19 %, about 1 h 12 min left at 2.0 kW - the house goes dark at 10 %."));
        assertThat(low.row().lowSocAlertedAt()).isEqualTo(T0.plusSeconds(30));
        assertThat(lower.notices()).isEmpty();
    }

    //every energy.outage.reminder-every from the last notification, not by SMS, with the newest reading
    @Test
    void should_remind_every_hour_while_it_lasts() {
        read(0, eps(64, -2100));
        read(1, eps(64, -2100));
        final SolaxState later = new SolaxState(134L, eps(52, -2000), T0.plusMinutes(59), true);
        at(T0.plusMinutes(59), later);

        final Decision early = at(T0.plusSeconds(15).plusMinutes(59).plusSeconds(59), later);
        final Decision reminder = at(T0.plusSeconds(15).plusHours(1), later);

        assertThat(early.notices()).isEmpty();
        assertThat(reminder.notices()).containsExactly(new Notice(Kind.REMINDER,
            "Grid still lost since 19:42 (1 h 0 min) - battery 52 %, about 5 h 34 min left at 2.0 kW."));
        assertThat(reminder.row().lastRemindedAt()).isEqualTo(T0.plusSeconds(15).plusHours(1));
    }

    //two readings with the grid there close it at the first of them - an info by SMS
    @Test
    void should_close_the_outage_on_the_second_reading_of_the_grid() {
        read(0, eps(64, -2100));
        read(1, eps(64, -2100));
        read(2, present(41, 3000));
        final Decision decision = read(3, present(41, 3000));

        assertThat(decision.row()).isEqualTo(new GridOutageEntity(null, T0, T0.plusSeconds(30), 64.0, 41.0, 41.0,
            T0.plusSeconds(15), null));
        assertThat(decision.notices()).containsExactly(new Notice(Kind.INFO_SMS,
            "Grid back at 19:42 after 0 min - battery 41 %."));
        assertThat(GridOutageService.stateOf(watch, false).state()).isEqualTo("present");
        assertThat(GridOutageService.stateOf(watch, false).since()).isEqualTo(T0.plusSeconds(30));
    }

    //a restart during an outage: the row read from the database is held, and the SOLAX closes it
    @Test
    void should_close_an_outage_read_after_a_start() {
        final GridOutageEntity stored = new GridOutageEntity(7L, T0.minusHours(2), null, 80.0, null, 55.0,
            T0.minusHours(1), null);
        watch = GridWatch.loaded(stored);

        assertThat(GridOutageService.stateOf(watch, false).state()).isEqualTo("lost");
        read(0, present(50, 0));
        final Decision decision = read(1, present(50, 0));

        //the reminder of the first pass was due (an hour after the stored one)
        assertThat(decision.row()).isEqualTo(new GridOutageEntity(7L, T0.minusHours(2), T0, 80.0, 50.0, 50.0,
            T0, null));
        assertThat(decision.notices()).containsExactly(new Notice(Kind.INFO_SMS,
            "Grid back at 19:42 after 2 h 0 min - battery 50 %."));
    }

    //an outage read after a start that still lasts: its reminder is due from the stored last notification
    @Test
    void should_remind_of_an_outage_read_after_a_start() {
        watch = GridWatch.loaded(new GridOutageEntity(7L, T0.minusHours(2), null, 80.0, null, 55.0,
            T0.minusHours(1), null));

        final Decision decision = read(0, eps(55, -1500));

        assertThat(decision.notices()).extracting(Notice::kind).containsExactly(Kind.REMINDER);
    }

    //after a start the grid is not known until two readings agree - never read as present
    @Test
    void should_know_the_grid_only_after_two_readings() {
        assertThat(GridOutageService.stateOf(watch, false).state()).isNull();
        read(0, present(80, 0));
        assertThat(GridOutageService.stateOf(watch, false).state()).isNull();
        read(1, present(80, 0));

        assertThat(GridOutageService.stateOf(watch, false).state()).isEqualTo("present");
        assertThat(GridOutageService.stateOf(watch, false).since()).isEqualTo(T0);
    }

    @Test
    void should_decide_nothing_without_a_reading() {
        final Decision decision = at(T0, new SolaxState(null, null, null, null));

        assertThat(decision.row()).isNull();
        assertThat(decision.notices()).isEmpty();
    }
}

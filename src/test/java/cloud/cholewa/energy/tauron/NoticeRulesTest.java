package cloud.cholewa.energy.tauron;

import cloud.cholewa.energy.database.PlannedOutageEntity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static cloud.cholewa.energy.tauron.TauronSamples.NOW;
import static org.assertj.core.api.Assertions.assertThat;

//what is told of a planned outage and when (HAS-337): the first notice by its kind and scope, the reminders by SMS the day
//before at 18:00 and 2 h before the start
class NoticeRulesTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 12, 8, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 12, 13, 0);
    private final NoticeRules rules = new NoticeRules(LocalTime.of(18, 0), Duration.ofHours(2));

    private static PlannedOutageEntity pending(final String notice) {
        return TauronSamples.row(7, TauronSamples.planned(TauronSamples.WORK, START, END)).withNotice(notice);
    }

    @Test
    void should_tell_a_new_planned_outage_of_the_street_as_an_alert_by_sms() {
        assertThat(rules.due(pending("new"), NOW)).hasValueSatisfying(notice -> {
            assertThat(notice.channel()).isEqualTo(NoticeRules.Channel.ALERT_SMS);
            assertThat(notice.text())
                .isEqualTo("Planned outage of the grid (Tauron): Thu 12 Nov 08:00-13:00. Testowo ul. Przykładowa");
            assertThat(notice.told().notice()).isNull();
            assertThat(notice.told().notifiedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void should_tell_a_failure_of_the_street_as_an_alert_without_sms() {
        assertThat(rules.due(pending("new").withKind("unplanned"), NOW))
            .hasValueSatisfying(notice -> assertThat(notice.channel()).isEqualTo(NoticeRules.Channel.ALERT));
    }

    //owner, 2026-11-03: the town alone is an info on Discord, no SMS
    @Test
    void should_tell_an_outage_of_the_town_as_an_info() {
        assertThat(rules.due(pending("new").withScope("town"), NOW)).hasValueSatisfying(notice -> {
            assertThat(notice.channel()).isEqualTo(NoticeRules.Channel.INFO);
            assertThat(notice.text()).startsWith("Planned outage in the town (Tauron), the street of the house not named");
        });
    }

    @Test
    void should_tell_a_change_and_a_move_by_sms_and_a_cancellation_as_an_info() {
        assertThat(rules.due(pending("changed"), NOW)).hasValueSatisfying(notice ->
            assertThat(notice.channel()).isEqualTo(NoticeRules.Channel.ALERT_SMS));
        assertThat(rules.due(pending("moved").withMovedFrom(START.minusDays(1)), NOW)).hasValueSatisfying(notice -> {
            assertThat(notice.channel()).isEqualTo(NoticeRules.Channel.ALERT_SMS);
            assertThat(notice.text()).startsWith(
                "Planned outage of the grid moved (Tauron) from Wed 11 Nov 08:00 to Thu 12 Nov 08:00-13:00.");
        });
        assertThat(rules.due(pending("cancelled").withClosedAt(NOW).withClosedAs("cancelled"), NOW))
            .hasValueSatisfying(notice -> assertThat(notice.channel()).isEqualTo(NoticeRules.Channel.INFO));
    }

    @Test
    void should_drop_the_notice_of_an_outage_already_over() {
        assertThat(rules.due(pending("new"), END)).hasValueSatisfying(notice -> {
            assertThat(notice.channel()).isEqualTo(NoticeRules.Channel.NONE);
            assertThat(notice.told().notice()).isNull();
            assertThat(notice.told().notifiedAt()).isNull();
        });
    }

    @Test
    void should_remind_the_day_before_at_six_in_the_evening() {
        final PlannedOutageEntity told = TauronSamples.told(7, START, END);

        assertThat(rules.due(told, LocalDateTime.of(2026, 11, 11, 17, 59))).isEmpty();
        assertThat(rules.due(told, LocalDateTime.of(2026, 11, 11, 18, 0))).hasValueSatisfying(notice -> {
            assertThat(notice.channel()).isEqualTo(NoticeRules.Channel.REMINDER_SMS);
            assertThat(notice.text()).isEqualTo("Reminder: the grid is off tomorrow, Thu 12 Nov 08:00-13:00 (Tauron, planned).");
            assertThat(notice.told().dayBeforeRemindedAt()).isEqualTo(LocalDateTime.of(2026, 11, 11, 18, 0));
            assertThat(notice.told().beforeStartRemindedAt()).isNull();
        });
        assertThat(rules.due(told.withDayBeforeRemindedAt(LocalDateTime.of(2026, 11, 11, 18, 0)),
            LocalDateTime.of(2026, 11, 11, 23, 0))).isEmpty();
    }

    @Test
    void should_remind_two_hours_before_the_start() {
        final PlannedOutageEntity told = TauronSamples.told(7, START, END)
            .withDayBeforeRemindedAt(LocalDateTime.of(2026, 11, 11, 18, 0));

        assertThat(rules.due(told, LocalDateTime.of(2026, 11, 12, 5, 59))).isEmpty();
        assertThat(rules.due(told, LocalDateTime.of(2026, 11, 12, 6, 0))).hasValueSatisfying(notice -> {
            assertThat(notice.text()).isEqualTo("Reminder: the grid goes off at 08:00, Thu 12 Nov 08:00-13:00 (Tauron, planned).");
            assertThat(notice.told().beforeStartRemindedAt()).isEqualTo(LocalDateTime.of(2026, 11, 12, 6, 0));
        });
        assertThat(rules.due(told, START)).isEmpty();
    }

    //down at 18:00: the reminder of the day before is not sent once the one before the start is due
    @Test
    void should_tell_the_later_reminder_alone_when_both_are_due() {
        final PlannedOutageEntity told = TauronSamples.told(7, START, END);

        assertThat(rules.due(told, LocalDateTime.of(2026, 11, 12, 7, 0))).hasValueSatisfying(notice -> {
            assertThat(notice.text()).startsWith("Reminder: the grid goes off at 08:00");
            assertThat(notice.told().dayBeforeRemindedAt()).isEqualTo(LocalDateTime.of(2026, 11, 12, 7, 0));
        });
    }

    //announced after 18:00 of the day before: its alert is the reminder of the evening
    @Test
    void should_not_remind_of_what_was_told_after_the_time_of_the_reminder() {
        final PlannedOutageEntity late = TauronSamples.told(7, START, END)
            .withNotifiedAt(LocalDateTime.of(2026, 11, 11, 19, 0));

        assertThat(rules.due(late, LocalDateTime.of(2026, 11, 11, 19, 1))).isEmpty();
        assertThat(rules.due(late, LocalDateTime.of(2026, 11, 12, 6, 0))).isPresent();
    }

    @Test
    void should_remind_of_no_failure_and_of_nothing_in_the_town() {
        final LocalDateTime evening = LocalDateTime.of(2026, 11, 11, 18, 0);

        assertThat(rules.due(TauronSamples.told(7, START, END).withKind("unplanned"), evening)).isEmpty();
        assertThat(rules.due(TauronSamples.told(7, START, END).withScope("town"), evening)).isEmpty();
        assertThat(rules.due(TauronSamples.told(7, START, END).withNotifiedAt(null), evening)).isEmpty();
    }

    @Test
    void should_word_an_outage_across_midnight_with_both_days() {
        final PlannedOutageEntity night = TauronSamples.told(7, LocalDateTime.of(2026, 11, 12, 22, 0),
            LocalDateTime.of(2026, 11, 13, 6, 0));

        assertThat(PlannedOutageMessages.span(night)).isEqualTo("Thu 12 Nov 22:00 - Fri 13 Nov 06:00");
    }
}

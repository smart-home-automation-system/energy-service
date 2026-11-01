package cloud.cholewa.energy.solaxcloud;

import cloud.cholewa.energy.database.SourceAlertEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//what the alarms of the SOLAX in the cloud are to the household (HAS-329): a new ongoing alarm an alert, one no longer
//listed an info, each list one notice a pass; decoded from the closed alarms of the house
class AlarmRulesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 11, 30);

    //the recorded closed alarm as if it were still going on
    private static CloudAlarm batPowerLow() {
        final CloudAlarm closed = CloudSamples.alarms("alarms-closed").records().getFirst();
        return new CloudAlarm(closed.alarmName(), closed.errorCode(), closed.alarmType(), closed.alarmLevel(), 1,
            closed.alarmStartTime(), null, closed.deviceSn());
    }

    @Test
    void should_alert_of_a_new_alarm_with_its_name_code_type_level_and_start() {
        final AlarmRules.Decision decision = AlarmRules.decide(List.of(batPowerLow()), List.of(), NOW);

        assertThat(decision.startedText()).isEqualTo("SOLAX alarm: Bat Power Low (error 17, type 1, level 2) since 10:12.");
        assertThat(decision.started()).singleElement().isEqualTo(new SourceAlertEntity(null, "solax", "alarm",
            LocalDateTime.of(2026, 10, 23, 10, 12, 36), NOW, null, "17|2026-10-23 10:12:36|Bat Power Low"));
        assertThat(decision.ended()).isEmpty();
        assertThat(decision.endedText()).isNull();
    }

    @Test
    void should_say_nothing_of_an_alarm_already_held() {
        final SourceAlertEntity held = new SourceAlertEntity(5L, "solax", "alarm",
            LocalDateTime.of(2026, 10, 23, 10, 12, 36), NOW.minusMinutes(15), null, AlarmRules.detail(batPowerLow()));

        final AlarmRules.Decision decision = AlarmRules.decide(List.of(batPowerLow()), List.of(held), NOW);

        assertThat(decision.started()).isEmpty();
        assertThat(decision.ended()).isEmpty();
    }

    @Test
    void should_tell_the_end_of_an_alarm_no_longer_listed() {
        final SourceAlertEntity held = new SourceAlertEntity(5L, "solax", "alarm",
            LocalDateTime.of(2026, 10, 23, 10, 12, 36), NOW.minusMinutes(15), null, AlarmRules.detail(batPowerLow()));

        final AlarmRules.Decision decision = AlarmRules.decide(List.of(), List.of(held), NOW);

        assertThat(decision.endedText())
            .isEqualTo("SOLAX alarm over: Bat Power Low (error 17) - from 10:12 to 11:30 (1 h 17 min).");
        assertThat(decision.ended()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(5L);
            assertThat(row.closedAt()).isEqualTo(NOW);
        });
    }

    //the same error again later is another alarm: the old one ends, the new one starts
    @Test
    void should_take_the_same_error_started_later_for_another_alarm() {
        final SourceAlertEntity held = new SourceAlertEntity(5L, "solax", "alarm",
            LocalDateTime.of(2026, 10, 22, 14, 45, 22), NOW.minusHours(1), null, "17|2026-10-22 14:45:22|Bat Power Low");

        final AlarmRules.Decision decision = AlarmRules.decide(List.of(batPowerLow()), List.of(held), NOW);

        assertThat(decision.started()).hasSize(1);
        assertThat(decision.ended()).hasSize(1);
    }
}

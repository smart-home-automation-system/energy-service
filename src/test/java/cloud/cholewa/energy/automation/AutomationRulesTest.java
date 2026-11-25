package cloud.cholewa.energy.automation;

import cloud.cholewa.energy.database.BatteryChargeRunEntity;
import cloud.cholewa.energy.error.EnergyError;
import cloud.cholewa.energy.error.EnergyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//the rules of the automatic charge (HAS-338): the planned start, what a pass does, the bounds of the settings
class AutomationRulesTest {

    private static final LocalDateTime OUTAGE = LocalDateTime.of(2026, 11, 12, 8, 0);

    //45 % to 100 % of 26.5 kWh at 5 kW is 175 min (174.9 up), plus an hour: 3 h 55 min before 08:00
    @Test
    void should_plan_the_start_from_the_soc_the_power_and_the_margin() {
        assertThat(AutomationRules.plannedStart(OUTAGE, 45.0, 100, 5.0, 60, 26.5))
            .contains(LocalDateTime.of(2026, 11, 12, 4, 5));
    }

    @Test
    void should_plan_nothing_for_a_battery_at_its_target() {
        assertThat(AutomationRules.plannedStart(OUTAGE, 100.0, 100, 5.0, 60, 26.5)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"false, 45, 04:05, 05:00, OFF", "true, , 04:05, 05:00, SOC_NOT_KNOWN", "true, 45, , 05:00, NOTHING_TO_CHARGE",
        "true, 45, 04:05, 04:04, WAIT", "true, 45, 04:05, 04:05, START"})
    void should_decide_what_a_pass_does(final boolean on, final Double soc, final String start, final String now,
                                         final AutomationRules.Step step) {
        final LocalDateTime planned = start == null ? null : LocalDateTime.of(2026, 11, 12, 0, 0).with(LocalTime.parse(start));
        assertThat(AutomationRules.stepOf(on, soc, planned, LocalDateTime.of(2026, 11, 12, 0, 0)
            .with(LocalTime.parse(now)))).isEqualTo(step);
    }

    @ParameterizedTest
    @CsvSource({"true, 9, 5.0, 60", "true, 101, 5.0, 60", "true, 100, 0.9, 60", "true, 100, 8.1, 60",
        "true, 100, 5.0, -1", "true, 100, 5.0, 721", ", 100, 5.0, 60"})
    void should_refuse_settings_out_of_bounds(final Boolean on, final Integer target, final Double power,
                                              final Integer margin) {
        assertThatThrownBy(() -> AutomationRules.check(on, target, power, margin))
            .isInstanceOfSatisfying(EnergyException.class, refused ->
                assertThat(refused.getError()).isEqualTo(EnergyError.INVALID_AUTOMATION));
    }

    @Test
    void should_take_settings_at_their_bounds() {
        AutomationRules.check(true, 10, 1.0, 0);
        AutomationRules.check(false, 100, 8.0, 720);
    }

    //HAS-478: the runs of an outage decide whether another one comes
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 4, 30);
    private static final Duration PAUSE = Duration.ofMinutes(5);

    private static BatteryChargeRunEntity run(final LocalDateTime endedAt, final String endedBy) {
        return new BatteryChargeRunEntity(1L, NOW.minusHours(1), "automation", 100, NOW.minusHours(1), OUTAGE, "r-1",
            null, null, 45.0, null, endedAt, endedBy, "failed".equals(endedBy) ? "DEVICE_FAILED" : null, null, endedAt,
            7L);
    }

    private static AutomationRules.Attempt attemptOf(final BatteryChargeRunEntity... runs) {
        return AutomationRules.attemptsOf(List.of(runs), NOW, 3, PAUSE).attempt();
    }

    @Test
    void should_start_the_first_run_of_an_outage() {
        assertThat(attemptOf()).isEqualTo(AutomationRules.Attempt.START);
    }

    @Test
    void should_start_nothing_while_a_run_of_the_outage_goes_on() {
        assertThat(attemptOf(run(NOW.minusMinutes(20), "failed"), run(null, null)))
            .isEqualTo(AutomationRules.Attempt.RUNNING);
    }

    @ParameterizedTest
    @CsvSource({"target", "window", "cancel"})
    void should_never_follow_a_run_that_was_no_failure(final String endedBy) {
        assertThat(attemptOf(run(NOW.minusMinutes(40), "failed"), run(NOW.minusMinutes(20), endedBy)))
            .isEqualTo(AutomationRules.Attempt.DONE);
    }

    //the pause counts from the end of the last failed run, its last minute included
    @Test
    void should_wait_for_the_pause_after_a_failed_run_and_then_start_again() {
        assertThat(attemptOf(run(NOW.minusMinutes(4), "failed"))).isEqualTo(AutomationRules.Attempt.WAIT);
        assertThat(attemptOf(run(NOW.minusMinutes(5), "failed"))).isEqualTo(AutomationRules.Attempt.START);
        assertThat(attemptOf(run(NOW.minusMinutes(30), "failed"), run(NOW.minusMinutes(4), "failed")))
            .isEqualTo(AutomationRules.Attempt.WAIT);
    }

    @Test
    void should_give_up_once_the_attempts_are_used_up() {
        final AutomationRules.Attempts attempts = AutomationRules.attemptsOf(List.of(
            run(NOW.minusMinutes(10), "failed"), run(NOW.minusMinutes(30), "failed"),
            run(NOW.minusMinutes(20), "failed")), NOW, 3, PAUSE);

        assertThat(attempts.attempt()).isEqualTo(AutomationRules.Attempt.GIVEN_UP);
        assertThat(attempts.firstFailed()).map(BatteryChargeRunEntity::endedAt).contains(NOW.minusMinutes(30));
        assertThat(attempts.lastFailed()).map(BatteryChargeRunEntity::endedAt).contains(NOW.minusMinutes(10));
    }

    //one attempt configured: the first failure is the give-up
    @Test
    void should_give_up_at_the_first_failure_with_one_attempt() {
        assertThat(AutomationRules.attemptsOf(List.of(run(NOW.minusMinutes(10), "failed")), NOW, 1, PAUSE).attempt())
            .isEqualTo(AutomationRules.Attempt.GIVEN_UP);
    }
}

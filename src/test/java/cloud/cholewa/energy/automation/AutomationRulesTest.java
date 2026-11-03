package cloud.cholewa.energy.automation;

import cloud.cholewa.energy.error.EnergyError;
import cloud.cholewa.energy.error.EnergyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;

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
        final LocalDateTime planned = start == null ? null : LocalDateTime.of(2026, 11, 12, 0, 0).with(java.time.LocalTime.parse(start));
        assertThat(AutomationRules.stepOf(on, soc, planned, LocalDateTime.of(2026, 11, 12, 0, 0)
            .with(java.time.LocalTime.parse(now)))).isEqualTo(step);
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
}

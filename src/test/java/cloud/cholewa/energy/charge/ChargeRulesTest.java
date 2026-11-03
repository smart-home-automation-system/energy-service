package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.error.EnergyError;
import cloud.cholewa.energy.error.EnergyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.LocalDateTime;

import static cloud.cholewa.energy.charge.ChargeSamples.CAPACITY;
import static cloud.cholewa.energy.charge.ChargeSamples.NOW;
import static cloud.cholewa.energy.charge.ChargeSamples.PROPERTIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//the rules of a charge (HAS-336): the window and its estimate, the end of a run, what a status of a command means
class ChargeRulesTest {

    //45 % to 100 % of 26.5 kWh is 14.575 kWh, at 5 kW 175 min (174.9 up), plus an hour
    @Test
    void should_estimate_the_time_to_the_target_with_the_margin() {
        assertThat(ChargeRules.estimate(45.0, 100, CAPACITY, PROPERTIES)).isEqualTo(Duration.ofMinutes(175 + 60));
    }

    @Test
    void should_open_the_window_at_the_minute_of_the_request() {
        assertThat(ChargeRules.window(NOW, 45.0, 100, null, CAPACITY, PROPERTIES))
            .isEqualTo(new ChargeRules.Window(LocalDateTime.of(2026, 11, 3, 14, 5), LocalDateTime.of(2026, 11, 3, 18, 0)));
    }

    @Test
    void should_end_the_window_at_until_when_asked() {
        assertThat(ChargeRules.window(NOW, 45.0, 100, LocalDateTime.of(2026, 11, 4, 6, 0, 40), CAPACITY, PROPERTIES)
            .end()).isEqualTo(LocalDateTime.of(2026, 11, 4, 6, 0));
    }

    @Test
    void should_refuse_an_until_in_the_past_or_beyond_the_longest_window() {
        assertThatThrownBy(() -> ChargeRules.window(NOW, 45.0, 100, NOW.minusMinutes(1), CAPACITY, PROPERTIES))
            .isInstanceOfSatisfying(EnergyException.class, refused ->
                assertThat(refused.getError()).isEqualTo(EnergyError.INVALID_CHARGE));
        final LocalDateTime tooFar = NOW.plusHours(23).plusMinutes(1);
        assertThatThrownBy(() -> ChargeRules.window(NOW, 45.0, 100, tooFar, CAPACITY, PROPERTIES))
            .hasMessage("until must be after now and at most 23 h ahead");
    }

    @ParameterizedTest
    @CsvSource({"9, dashboard", "101, dashboard", "100, someone"})
    void should_refuse_a_target_or_a_source_out_of_bounds(final int target, final String source) {
        assertThatThrownBy(() -> ChargeRules.check(target, source))
            .isInstanceOfSatisfying(EnergyException.class, refused ->
                assertThat(refused.getError()).isEqualTo(EnergyError.INVALID_CHARGE));
    }

    @Test
    void should_end_a_charge_by_its_target_then_by_its_window() {
        assertThat(ChargeRules.endOf(ChargeSamples.charging(), 99.0, NOW.plusHours(1))).isEmpty();
        assertThat(ChargeRules.endOf(ChargeSamples.charging(), 100.0, NOW.plusHours(1))).contains("target");
        assertThat(ChargeRules.endOf(ChargeSamples.charging(), null, LocalDateTime.of(2026, 11, 3, 19, 30)))
            .contains("window");
    }

    @ParameterizedTest
    @CsvSource({"4, false, EXECUTING", "1, false, OFFLINE", "2, false, NOT_TAKEN", "5, false, NOT_TAKEN",
        "6, false, UNCERTAIN", "3, true, UNCERTAIN", "3, false, WAITING", ", false, WAITING", ", true, UNCERTAIN"})
    void should_read_what_a_status_of_a_command_means(final Integer status, final boolean late,
                                                      final ChargeRules.Delivery delivery) {
        assertThat(ChargeRules.deliveryOf(status, late)).isEqualTo(delivery);
    }

    @Test
    void should_tell_the_minutes_left() {
        assertThat(ChargeRules.minutesLeft(90.0, 100, CAPACITY, PROPERTIES)).contains(32L);
        assertThat(ChargeRules.minutesLeft(null, 100, CAPACITY, PROPERTIES)).isEmpty();
    }
}

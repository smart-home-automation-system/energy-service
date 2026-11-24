package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.error.EnergyError;
import cloud.cholewa.energy.error.EnergyException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//the rules of the battery reserve (HAS-494), copied by the dashboard: the level 10 - 100 by 5, until only for a reserve
//switched on, after now and at most 7 days ahead
class ReserveRulesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 10, 0);

    private static void refused(final Boolean enabled, final Integer level, final LocalDateTime until,
                                final String reason) {
        assertThatThrownBy(() -> ReserveRules.check(enabled, level, until, NOW))
            .isInstanceOfSatisfying(EnergyException.class, refused -> {
                assertThat(refused.getError()).isEqualTo(EnergyError.INVALID_RESERVE);
                assertThat(refused.getMessage()).isEqualTo(reason);
            });
    }

    @Test
    void should_take_the_bounds_of_the_level_by_steps_of_five() {
        assertThatCode(() -> ReserveRules.check(true, 10, null, NOW)).doesNotThrowAnyException();
        assertThatCode(() -> ReserveRules.check(true, 100, null, NOW)).doesNotThrowAnyException();
        assertThatCode(() -> ReserveRules.check(false, 85, null, NOW)).doesNotThrowAnyException();
    }

    @Test
    void should_refuse_a_level_out_of_bounds_or_between_steps() {
        final String reason = "level must be between 10 and 100 in steps of 5";
        refused(true, 5, null, reason);
        refused(true, 105, null, reason);
        refused(true, 83, null, reason);
    }

    @Test
    void should_require_the_switch_and_the_level() {
        refused(null, 80, null, "enabled and level are required");
        refused(true, null, null, "enabled and level are required");
    }

    @Test
    void should_take_until_after_now_and_at_most_seven_days_ahead() {
        assertThatCode(() -> ReserveRules.check(true, 80, NOW.plusDays(7), NOW)).doesNotThrowAnyException();
        final String reason = "until must be after now and at most 7 days ahead";
        refused(true, 80, NOW, reason);
        refused(true, 80, NOW.plusDays(7).plusMinutes(1), reason);
    }

    @Test
    void should_refuse_until_for_a_reserve_switched_off() {
        refused(false, 80, NOW.plusHours(2), "until is only for a reserve switched on");
    }

    @Test
    void should_hold_while_switched_on_and_before_until() {
        assertThat(ReserveRules.inForce(true, null, NOW)).isTrue();
        assertThat(ReserveRules.inForce(true, NOW.plusMinutes(1), NOW)).isTrue();
        assertThat(ReserveRules.inForce(true, NOW, NOW)).isFalse();
        assertThat(ReserveRules.inForce(false, null, NOW)).isFalse();
    }
}

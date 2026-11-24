package cloud.cholewa.energy.frequency;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

//The grid frequency off its band (HAS-503). A bare duration is minutes.
//low / high - Hz of the grid meter outside which the frequency is off (49.8 / 50.2; the continental grid holds 50 Hz
//within 50 mHz - on 2026-11-24 a generator of Tauron fed the house at 51.0 Hz and both inverters refused to connect)
//offAfter - how long it is off before it is an alert (PT2M; 1 min - 1 h)
//backAfter - how long it is back inside the band before the episode ends with an info (PT5M; 1 min - 1 h)
//reminderEvery - a reminder while it lasts (PT1H; 10 min - 24 h)
@Validated
@ConfigurationProperties(prefix = "energy.frequency")
public record FrequencyProperties(
    @DecimalMin("45.0") @DecimalMax("49.99") double low,
    @DecimalMin("50.01") @DecimalMax("55.0") double high,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(hours = 1) Duration offAfter,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(hours = 1) Duration backAfter,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 10) @DurationMax(hours = 24) Duration reminderEvery
) {

    //a known frequency outside the band
    boolean off(final double hz) {
        return hz < low || hz > high;
    }
}

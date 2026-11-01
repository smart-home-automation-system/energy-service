package cloud.cholewa.energy.outage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

//The outages of the grid (HAS-334).
//lowSoc - a battery below this % while the grid is lost is one more alert by SMS (20; 11 - 99, above the 10 % the
//SOLAX stops the EPS at)
//reminderEvery - a reminder while the grid is lost, 10 min to 24 h (a bare number is minutes)
@Validated
@ConfigurationProperties(prefix = "energy.outage")
public record OutageProperties(
    @NotNull @Min(11) @Max(99) Integer lowSoc,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 10) @DurationMax(hours = 24) Duration reminderEvery
) {
}

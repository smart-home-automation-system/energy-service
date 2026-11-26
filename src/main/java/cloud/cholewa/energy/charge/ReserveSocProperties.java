package cloud.cholewa.energy.charge;

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

//The warning of a battery below its reserve (HAS-495). A bare duration is minutes.
//tolerance - how far below the level of the reserve the battery may be before it counts as not held, % (5; 1 - 20):
//the inverter holds its floor with a margin and the SOC it reports moves by a point or two
//warnAfter - how long the battery is below the level less the tolerance and not charging before it is a warning
//(PT30M; 10 min - 6 h)
//reminderEvery - a reminder while it lasts (PT1H; 10 min - 24 h)
@Validated
@ConfigurationProperties(prefix = "energy.charge.soc")
public record ReserveSocProperties(
    @NotNull @Min(1) @Max(20) Integer tolerance,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 10) @DurationMax(hours = 6) Duration warnAfter,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 10) @DurationMax(hours = 24) Duration reminderEvery
) {
}

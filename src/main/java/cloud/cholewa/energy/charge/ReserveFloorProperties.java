package cloud.cholewa.energy.charge;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

//The warning of a battery reserve the inverter does not hold (HAS-518). A bare duration is minutes.
//warnAfter - how long the inverter is out of Back Up, or its Min SoC below the level of the reserve, before it is a
//warning (PT10M; 2 min - 1 h): the write of a reserve and the read after it need a minute
//reminderEvery - a reminder while it lasts (PT1H; 10 min - 24 h)
@Validated
@ConfigurationProperties(prefix = "energy.charge.floor")
public record ReserveFloorProperties(
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 2) @DurationMax(hours = 1) Duration warnAfter,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 10) @DurationMax(hours = 24) Duration reminderEvery
) {
}

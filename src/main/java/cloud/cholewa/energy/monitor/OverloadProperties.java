package cloud.cholewa.energy.monitor;

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

//The overload of a phase of the grid meter (HAS-332).
//phaseCurrentWarn - a current above this many A warns (22; the main fuses are 3 x 25 A, the board of the inverter
//20 A a phase); 1 - 63 A
//overloadAfter - how long above the current is an overload, and how long below it ends one (PT3M; 1 - 60 min, a bare
//number is minutes)
@Validated
@ConfigurationProperties(prefix = "energy.grid")
public record OverloadProperties(
    @NotNull @DecimalMin("1") @DecimalMax("63") Double phaseCurrentWarn,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(hours = 1) Duration overloadAfter
) {
}

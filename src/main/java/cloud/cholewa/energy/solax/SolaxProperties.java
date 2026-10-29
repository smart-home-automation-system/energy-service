package cloud.cholewa.energy.solax;

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

//The SOLAX inverter read over the local API of its dongle (HAS-328). The inverter itself comes from the registry.
//interval - between two reads, 10 to 60 s (a bare number is seconds)
//staleAfter - a reading older than this is served as stale, 30 s to 10 min
//batteryCapacity - the usable kWh of the battery of the plant (SolaxCloud: 26.5), for the energy left from the SOC
@Validated
@ConfigurationProperties(prefix = "energy.solax")
public record SolaxProperties(
    @NotNull @DurationUnit(ChronoUnit.SECONDS) @DurationMin(seconds = 10) @DurationMax(seconds = 60) Duration interval,
    @NotNull @DurationUnit(ChronoUnit.SECONDS) @DurationMin(seconds = 30) @DurationMax(minutes = 10) Duration staleAfter,
    @NotNull @DecimalMin("1.0") @DecimalMax("100.0") Double batteryCapacity
) {
}

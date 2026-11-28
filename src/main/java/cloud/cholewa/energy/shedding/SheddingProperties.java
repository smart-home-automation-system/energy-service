package cloud.cholewa.energy.shedding;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

//The load shedding during an outage of the grid (HAS-523).
//stableAfter - the grid back without a break for this long ends the load shedding, ten minutes (owner, 2026-11-25);
//1 - 60 min, a bare number in minutes
@Validated
@ConfigurationProperties(prefix = "energy.shedding")
public record SheddingProperties(
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(minutes = 60) Duration stableAfter
) {
}

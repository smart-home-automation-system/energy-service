package cloud.cholewa.energy.monitor;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

//The silence of the sources (HAS-332); a bare number is minutes.
//solaxSilentAfter - the dongle of the SOLAX not answering for this long is an alert (PT5M; 1 min - 24 h)
//meterSilentAfter - the grid meter without a message and not answering over RPC for this long (PT5M; 1 min - 24 h)
//reminderEvery - a reminder while a source stays silent (PT1H; 10 min - 24 h)
@Validated
@ConfigurationProperties(prefix = "energy.availability")
public record AvailabilityProperties(
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(hours = 24) Duration solaxSilentAfter,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(hours = 24) Duration meterSilentAfter,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 10) @DurationMax(hours = 24) Duration reminderEvery
) {
}

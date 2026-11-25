package cloud.cholewa.energy.automation;

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

//The attempts of the automatic charge before a planned outage (HAS-478).
//maxAttempts - the runs an outage gets at most, the failed ones counted, three: a charge that failed is tried again
//retryEvery - the pause after a failed run ended before the next one starts, five minutes, a bare number in minutes: the
//cloud or the inverter gets the time to come back, and the write of the baseline its minute
@Validated
@ConfigurationProperties(prefix = "energy.automation")
public record AutomationProperties(
    @NotNull @Min(1) @Max(5) Integer maxAttempts,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(minutes = 30) Duration retryEvery
) {
}

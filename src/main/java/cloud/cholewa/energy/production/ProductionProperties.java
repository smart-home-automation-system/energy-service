package cloud.cholewa.energy.production;

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

//The inverter that does not produce in the sun (HAS-333). A bare duration is minutes.
//silence - how long the sun shines and the inverter gives less than minPower before it is an alert (PT15M; 5 min - 2 h)
//illuminanceThreshold - lx of the weather station above which the sun shines (20 000; the station showed about 25 000 at
//the noon of a sunny day of late October, 5 000 on an overcast one - tuned with the owner on real days)
//minPower - W of the panels below which an inverter does not produce (100; the SOLAX 8.24 kWp, the SOFAR 5 kWp)
//reminderEvery - a reminder while it lasts (PT1H; 10 min - 24 h)
@Validated
@ConfigurationProperties(prefix = "energy.production")
public record ProductionProperties(
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 5) @DurationMax(hours = 2) Duration silence,
    @Min(1000) @Max(150000) int illuminanceThreshold,
    @Min(10) @Max(5000) int minPower,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 10) @DurationMax(hours = 24) Duration reminderEvery
) {
}

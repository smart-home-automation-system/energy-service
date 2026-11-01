package cloud.cholewa.energy.solaxcloud;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.time.temporal.ChronoUnit;

//The SolaX Developer Platform (HAS-329).
//baseUrl - the data centre of the account (https://openapi-eu.solaxcloud.com)
//clientId, clientSecret - the application of the owner (secret energy, no default); plantId - the plant of the house
//interval - the inverter and its battery read (PT5M; 5 - 60 min, a bare number is minutes)
//alarmInterval - the alarms of the plant read (PT15M; 5 - 60 min)
//staleAfter - a cloud reading older than this is not served (PT15M; 5 min - 1 h)
//ignoredAlarmCodes - the error codes of the alarms the household is not told of: 17 "Bat Power Low" comes with the
//battery at its floor, almost every day (owner, 2026-11-01)
@Validated
@ConfigurationProperties(prefix = "energy.solax.cloud")
public record SolaxCloudProperties(
    @NotNull URI baseUrl,
    @NotBlank String clientId,
    @NotBlank String clientSecret,
    @NotBlank String plantId,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 5) @DurationMax(hours = 1) Duration interval,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 5) @DurationMax(hours = 1) Duration alarmInterval,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 5) @DurationMax(hours = 1) Duration staleAfter,
    @NotNull List<String> ignoredAlarmCodes
) {
}

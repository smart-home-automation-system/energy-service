package cloud.cholewa.energy.sofar;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

//The SOFAR inverter from the Solarman Open API (HAS-330).
//baseUrl - the international data centre (https://globalapi.solarmanpv.com)
//appId, appSecret - the application Solarman granted the owner; email, passwordSha256 - the account of the plant, the
//password as the lowercase SHA-256 the API takes (secret energy, no default)
//interval - the inverter read (PT5M, the logger uploads every 5 min; 1 - 60 min, a bare number is minutes)
//staleAfter - a reading collected longer ago is served as stale (PT15M; 5 min - 1 h)
@Validated
@ConfigurationProperties(prefix = "energy.sofar.cloud")
public record SolarmanProperties(
    @NotNull URI baseUrl,
    @NotBlank String appId,
    @NotBlank String appSecret,
    @NotBlank String email,
    @NotNull @Pattern(regexp = "[0-9a-f]{64}", message = "must be the lowercase SHA-256 of the password")
    String passwordSha256,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(hours = 1) Duration interval,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 5) @DurationMax(hours = 1) Duration staleAfter
) {
}

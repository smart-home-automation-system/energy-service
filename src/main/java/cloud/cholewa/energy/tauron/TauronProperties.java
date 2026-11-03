package cloud.cholewa.energy.tauron;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

//The planned outages Tauron Dystrybucja announces for the address of the house (HAS-337).
//baseUrl - the public site of Tauron Dystrybucja (https://www.tauron-dystrybucja.pl), no login
//city, street, houseNo - the address of the house as Tauron's register names it ("Przykładowa", the street without
//"ul."); a private fact - the manifest gives it (tauron-city / -street / -house-no), the public configuration does not
//interval - how often the announcements are read (PT1H; 15 min - 24 h, a bare number is minutes)
//aheadDays - how far ahead they are asked for (30; 1 - 60 days)
//alertAfter - reads failing for this long are an alert (PT24H; 1 h - 7 days, a bare number is hours)
//reminderAt - the reminder on the day before an outage, house time (18:00)
//remindBefore - the reminder before its start (PT2H; 30 min - 6 h, a bare number is minutes)
@Validated
@ConfigurationProperties(prefix = "energy.tauron")
public record TauronProperties(
    @NotNull URI baseUrl,
    @NotBlank String city,
    @NotBlank String street,
    @NotBlank String houseNo,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 15) @DurationMax(hours = 24) Duration interval,
    @NotNull @Min(1) @Max(60) Integer aheadDays,
    @NotNull @DurationUnit(ChronoUnit.HOURS) @DurationMin(hours = 1) @DurationMax(days = 7) Duration alertAfter,
    @NotNull LocalTime reminderAt,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 30) @DurationMax(hours = 6) Duration remindBefore
) {
}

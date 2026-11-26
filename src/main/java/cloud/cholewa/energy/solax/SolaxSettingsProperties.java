package cloud.cholewa.energy.solax;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

//The settings of the SOLAX read from its dongle (HAS-518).
//interval - between two reads, 1 to 60 min (a bare number is minutes)
//afterWrite - how long after a write of the service to the inverter the settings are read again, 5 s to 2 min (a bare
//number is seconds): the SolaX cloud needs a few seconds to hand a command to the inverter
//staleAfter - settings read longer ago are not known, 5 min to 2 h (a bare number is minutes); above the interval, so
//that one failed read does not make them unknown
@Validated
@ConfigurationProperties(prefix = "energy.solax.settings")
public record SolaxSettingsProperties(
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(minutes = 60) Duration interval,
    @NotNull @DurationUnit(ChronoUnit.SECONDS) @DurationMin(seconds = 5) @DurationMax(minutes = 2) Duration afterWrite,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 5) @DurationMax(hours = 2) Duration staleAfter
) {
}

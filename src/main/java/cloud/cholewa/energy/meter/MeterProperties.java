package cloud.cholewa.energy.meter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

//The grid meter (Shelly Pro 3EM at the grid entry, HAS-327). It is known by its MQTT id and its address from
//the manifest, not by a second entry of the device registry: the registry has it already, as the BTHome gateway
//"Grid power" (79) of the two gate sensors of the garage, and an entry stays one device.
//deviceId - the id of its topics (shellypro3em-<mac>), matched case-insensitively
//address - where its RPC answers, for the reconciliation
//staleAfter - a reading older than this is served as stale (the meter publishes about every 5 s); a bare number
//is seconds, 30 s to 1 h
@Validated
@ConfigurationProperties(prefix = "energy.meter")
public record MeterProperties(
    @NotBlank String deviceId,
    @NotBlank String address,
    @NotNull @DurationUnit(ChronoUnit.SECONDS) @DurationMin(seconds = 30) @DurationMax(hours = 1) Duration staleAfter
) {
}

package cloud.cholewa.energy.forecast;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

//The irradiance forecast of the two planes of panels (HAS-371).
//baseUrl - the public API of Open-Meteo (https://api.open-meteo.com), no key, no account
//interval - how often both planes are fetched, an hour, from 15 minutes to 6 hours, a bare number in minutes
//solax, sofar - the plane of the panels of each inverter: from the manifest (pv-solax-tilt, ...), no default
@Validated
@ConfigurationProperties(prefix = "energy.forecast")
public record ForecastProperties(
    @NotNull URI baseUrl,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 15) @DurationMax(hours = 6) Duration interval,
    @NotNull @Valid Plane solax,
    @NotNull @Valid Plane sofar
) {

    //tilt - degrees from the horizontal, 0 to 90; azimuth - degrees as Open-Meteo counts them, 0 south, -90 east, 90 west,
    //180 north; peakKwp - the peak power of the panels of the plane, above 0
    public record Plane(
        @NotNull @DecimalMin("0") @DecimalMax("90") Double tilt,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double azimuth,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") Double peakKwp
    ) {
    }

    //the planes by the inverter they feed, the SOLAX first - the order they are fetched in
    public Map<String, Plane> planes() {
        final Map<String, Plane> planes = new LinkedHashMap<>();
        planes.put("solax", solax);
        planes.put("sofar", sofar);
        return planes;
    }
}

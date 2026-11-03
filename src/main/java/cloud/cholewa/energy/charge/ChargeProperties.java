package cloud.cholewa.energy.charge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

//The charge of the SOLAX battery from the grid on demand (HAS-336).
//powerKw - the power the battery charges at from the grid, for the estimate of the window, 5 kW (owner 2026-10-23)
//margin - added to the estimate, an hour, up to three, a bare number in minutes
//longest - the longest window of a charge, 23 hours: the windows of the inverter are times of a day
//deliveryTimeout - how long the result of a command is asked for before the run fails, two minutes
//baseline - the Self Use settings of the inverter the service sets back after a run (owner 2026-11-03, read in
//SolaxCloud): the API reads none of them, and a write replaces them whole
@Validated
@ConfigurationProperties(prefix = "energy.charge")
public record ChargeProperties(
    @NotNull @DecimalMin("1.0") @DecimalMax("10.0") Double powerKw,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 0) @DurationMax(hours = 3) Duration margin,
    @NotNull @DurationUnit(ChronoUnit.HOURS) @DurationMin(hours = 1) @DurationMax(hours = 23) Duration longest,
    @NotNull @DurationUnit(ChronoUnit.SECONDS) @DurationMin(seconds = 30) @DurationMax(minutes = 10)
    Duration deliveryTimeout,
    @NotNull @Valid Baseline baseline
) {

    //The floor of the battery, the grid charge the inverter keeps by itself and its limit, and the window the battery may
    //give the house in
    public record Baseline(
        @NotNull @Min(10) @Max(100) Integer minSoc,
        @NotNull Boolean chargeFromGrid,
        @NotNull @Min(10) @Max(100) Integer chargeUpperSoc,
        @NotNull LocalTime dischargeStart,
        @NotNull LocalTime dischargeEnd
    ) {
    }
}

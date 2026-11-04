package cloud.cholewa.energy.forecast;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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
import java.time.temporal.ChronoUnit;

//How the stored forecast is turned into the answer of GET /forecast (HAS-372) - the same prefix as ForecastProperties,
//which holds the fetch; each binds its own keys.
//initialPerformanceRatio - the share of the potential of the panels an inverter is taken to produce before it is
//calibrated, 0.3 to 1.0 (0.8)
//calibrationDays - the closed days the ratio is computed on, 7 to 90 (28); minCalibrationDays - fewer keep the initial
//ratio, 1 to calibrationDays (5)
//staleAfter - a forecast fetched longer ago is stale, 1 to 24 hours (PT3H); a bare number is minutes
//bands - the coverage of the house load below which a day is none, poor and fair, ascending (0.10, 0.40, 0.80)
@Validated
@ConfigurationProperties(prefix = "energy.forecast")
public record OutlookProperties(
    @NotNull @DecimalMin("0.3") @DecimalMax("1.0") Double initialPerformanceRatio,
    @NotNull @Min(7) @Max(90) Integer calibrationDays,
    @NotNull @Min(1) Integer minCalibrationDays,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(hours = 1) @DurationMax(hours = 24) Duration staleAfter,
    @NotNull @Valid ForecastRules.Bands bands
) {

    @AssertTrue(message = "min-calibration-days must not be above calibration-days")
    boolean isMinCalibrationDaysWithinTheWindow() {
        return minCalibrationDays == null || calibrationDays == null || minCalibrationDays <= calibrationDays;
    }
}

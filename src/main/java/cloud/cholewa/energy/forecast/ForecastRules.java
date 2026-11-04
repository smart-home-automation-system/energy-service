package cloud.cholewa.energy.forecast;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

//The coming days judged against the house load (HAS-372), pure:
//- the reference is the mean house load of the closed days given (the last seven, fewer when fewer exist), and without
//  one no day has a coverage nor a category
//- the coverage is the kWh of the day divided by the reference, its category by the bands, each "below" exclusive
//- sunBackOn - the first day that is fair or good, null when none of the days is; daysWithoutSun - the days before it,
//  every day given when none is sunny; both null without a category on any day
public final class ForecastRules {

    private ForecastRules() {
    }

    //the coverage below which a day is none, poor and fair, 0 to 10, ascending; good from fairBelow on
    public record Bands(
        @NotNull @DecimalMin("0") @DecimalMax("10") Double noneBelow,
        @NotNull @DecimalMin("0") @DecimalMax("10") Double poorBelow,
        @NotNull @DecimalMin("0") @DecimalMax("10") Double fairBelow
    ) {

        @AssertTrue(message = "the bands must be ascending: none-below < poor-below < fair-below")
        boolean isAscending() {
            return noneBelow == null || poorBelow == null || fairBelow == null
                || noneBelow < poorBelow && poorBelow < fairBelow;
        }
    }

    public record Reference(double kwh, int days) {
    }

    public record Outlook(LocalDate sunBackOn, Integer daysWithoutSun) {
    }

    //the mean of the house loads known, null when none is
    public static Reference reference(final List<Double> houseLoads) {
        final List<Double> known = houseLoads.stream().filter(Objects::nonNull).toList();
        return known.isEmpty() ? null
            : new Reference(known.stream().mapToDouble(Double::doubleValue).average().orElseThrow(), known.size());
    }

    //null without the kWh of the day, or without a reference above 0
    public static Double coverage(final Double kwh, final Reference reference) {
        return kwh == null || reference == null || reference.kwh() <= 0 ? null : kwh / reference.kwh();
    }

    public static ForecastCategory category(final Double coverage, final Bands bands) {
        if (coverage == null) {
            return null;
        }
        if (coverage < bands.noneBelow()) {
            return ForecastCategory.NONE;
        }
        if (coverage < bands.poorBelow()) {
            return ForecastCategory.POOR;
        }
        return coverage < bands.fairBelow() ? ForecastCategory.FAIR : ForecastCategory.GOOD;
    }

    //the days and their categories in the same order, from today
    public static Outlook outlook(final List<LocalDate> days, final List<ForecastCategory> categories) {
        if (categories.stream().allMatch(Objects::isNull)) {
            return new Outlook(null, null);
        }
        for (int i = 0; i < days.size(); i++) {
            final ForecastCategory category = categories.get(i);
            if (category != null && category.sunny()) {
                return new Outlook(days.get(i), i);
            }
        }
        return new Outlook(null, days.size());
    }
}

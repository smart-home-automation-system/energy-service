package cloud.cholewa.energy.forecast;

import java.util.List;
import java.util.Objects;

//The energy the forecast gives (HAS-372), pure: the kWh of an hour = the peak power of the plane x its irradiance / 1000
//x the performance ratio of the inverter - the mean irradiance of an hour in W/m2 is its energy in Wh/m2, and the peak
//power is rated at 1000 W/m2. A day is the sum of its hours. The ratio is applied here, on read, never stored with the
//forecast: a new calibration changes every answer at once
public final class ForecastModel {

    static final double RATED_IRRADIANCE = 1000;

    private ForecastModel() {
    }

    public static double kwh(final double peakKwp, final double irradiance, final double ratio) {
        return peakKwp * irradiance / RATED_IRRADIANCE * ratio;
    }

    //the sum of the hours whose irradiance is known, null when none is
    public static Double kwh(final double peakKwp, final List<Double> irradiances, final double ratio) {
        final List<Double> known = irradiances.stream().filter(Objects::nonNull).toList();
        return known.isEmpty() ? null
            : known.stream().mapToDouble(irradiance -> kwh(peakKwp, irradiance, ratio)).sum();
    }
}

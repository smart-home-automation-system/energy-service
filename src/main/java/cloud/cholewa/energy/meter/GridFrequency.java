package cloud.cholewa.energy.meter;

import java.util.Objects;
import java.util.stream.Stream;

//The one frequency of the grid meter (HAS-503, HAS-506): of its three phases the one furthest from 50 Hz - what the
//frequency alert judges and what grid_reading stores for the history, so the chart and the alert never read the meter
//differently. Pinned by GridFrequencyTest
public final class GridFrequency {

    private static final double NOMINAL_HZ = 50.0;

    private GridFrequency() {
    }

    //null when no phase is known
    public static Double furthestFromNominal(final Double a, final Double b, final Double c) {
        return Stream.of(a, b, c)
            .filter(Objects::nonNull)
            .max((left, right) -> Double.compare(Math.abs(left - NOMINAL_HZ), Math.abs(right - NOMINAL_HZ)))
            .orElse(null);
    }
}

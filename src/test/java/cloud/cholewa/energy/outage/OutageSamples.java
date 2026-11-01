package cloud.cholewa.energy.outage;

import cloud.cholewa.energy.solax.SolaxReading;
import cloud.cholewa.energy.solax.SolaxSamples;

import java.time.Duration;

//Readings of the SOLAX for the outage tests (HAS-334): the evening sample of the house with the run mode, the voltages
//at the grid connection point and the battery set as a test needs them
final class OutageSamples {

    static final double CAPACITY = 26.5;
    static final OutageProperties PROPERTIES = new OutageProperties(20, Duration.ofHours(1));

    private static final SolaxReading EVENING = SolaxSamples.reading(SolaxSamples.EVENING);

    private OutageSamples() {
    }

    //the grid there: run mode normal, 230 V on every phase
    static SolaxReading present(final double soc, final double batteryPower) {
        return reading(2, 230.0, 230.0, 230.0, soc, batteryPower);
    }

    //the house in EPS: run mode 7, no voltage at the grid connection point
    static SolaxReading eps(final double soc, final double batteryPower) {
        return reading(7, 0.0, 0.0, 0.0, soc, batteryPower);
    }

    static SolaxReading reading(final int runMode, final Double a, final Double b, final Double c, final Double soc,
                                final Double batteryPower) {
        final SolaxReading.Battery battery = EVENING.battery();
        return new SolaxReading(EVENING.inverterSerial(), runMode, EVENING.a(), EVENING.b(), EVENING.c(),
            EVENING.frequency(), EVENING.powerFactor(), EVENING.acPower(), EVENING.temperature(),
            EVENING.dailyYield(), EVENING.totalYield(), EVENING.pv1(), EVENING.pv2(), EVENING.gridPower(),
            new SolaxReading.Battery(soc, battery.soh(), batteryPower, battery.voltage(), battery.current(),
                battery.temperature(), battery.cycles(), battery.chargedToday(), battery.dischargedToday()),
            new SolaxReading.GridPoint(a, b, c));
    }
}

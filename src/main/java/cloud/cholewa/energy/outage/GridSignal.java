package cloud.cholewa.energy.outage;

import cloud.cholewa.energy.solax.SolaxReading;

import java.util.Objects;
import java.util.stream.Stream;

//What one reading of the SOLAX says of the grid (HAS-334) - only the inverter knows it: the Pro 3EM goes dark with an
//empty battery, and the cluster runs in the house.
//LOST - the run mode is EPS check (6) or EPS (7), or the voltage of all three phases at the grid connection point is
//below 100 V.
//PRESENT - not in EPS and all three voltages at or above 100 V, in any other run mode: the battery empty, the SOLAX
//answered run mode 21 (not in its list) while the house took from the grid (sample of 2026-10-29), so "run mode 2" of
//the task would never see the grid back then.
//NOT_KNOWN - a voltage missing, or one or two phases down: neither, the state holds
enum GridSignal {
    LOST,
    PRESENT,
    NOT_KNOWN;

    static final double LIVE_VOLTS = 100.0;
    private static final int EPS_CHECK = 6;
    private static final int EPS = 7;

    static GridSignal of(final SolaxReading reading) {
        if (reading.runModeCode() == EPS_CHECK || reading.runModeCode() == EPS) {
            return LOST;
        }
        final SolaxReading.GridPoint point = reading.gridPoint();
        if (point == null || Stream.of(point.a(), point.b(), point.c()).anyMatch(Objects::isNull)) {
            return NOT_KNOWN;
        }
        final long live = Stream.of(point.a(), point.b(), point.c()).filter(volts -> volts >= LIVE_VOLTS).count();
        if (live == 0) {
            return LOST;
        }
        return live == 3 ? PRESENT : NOT_KNOWN;
    }
}

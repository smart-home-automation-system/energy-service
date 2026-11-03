package cloud.cholewa.energy.automation;

import cloud.cholewa.energy.error.EnergyException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

//The rules of the automatic charge before a planned outage (HAS-338), without I/O.
//The planned start: the start of the outage less the time to the target - the SOC to the target of the usable capacity at
//the charge power, whole minutes up - less the margin; none while the SOC is at the target already. The settings: the
//target 10 - 100 %, the power 1 - 8 kW, the margin 0 - 720 min (INVALID_AUTOMATION with the reason)
public final class AutomationRules {

    static final int LOWEST_TARGET = 10;
    static final int HIGHEST_TARGET = 100;
    static final double LOWEST_POWER = 1.0;
    static final double HIGHEST_POWER = 8.0;
    static final int LONGEST_MARGIN = 720;

    private AutomationRules() {
    }

    public static Optional<LocalDateTime> plannedStart(final LocalDateTime outageStart, final double soc,
                                                       final int targetSoc, final double powerKw,
                                                       final int marginMinutes, final double capacityKwh) {
        if (soc >= targetSoc) {
            return Optional.empty();
        }
        final double kwh = (targetSoc - soc) / 100 * capacityKwh;
        final long minutes = (long) Math.ceil(kwh / powerKw * 60);
        return Optional.of(outageStart.minus(Duration.ofMinutes(minutes + marginMinutes)).truncatedTo(ChronoUnit.MINUTES));
    }

    //what a pass does: nothing with the switch off; wait before the planned start; tell the SOC is not known; nothing while
    //the battery is at the target; start
    enum Step { OFF, WAIT, SOC_NOT_KNOWN, NOTHING_TO_CHARGE, START }

    static Step stepOf(final boolean on, final Double soc, final LocalDateTime plannedStart, final LocalDateTime now) {
        if (!on) {
            return Step.OFF;
        }
        if (soc == null) {
            return Step.SOC_NOT_KNOWN;
        }
        if (plannedStart == null) {
            return Step.NOTHING_TO_CHARGE;
        }
        return now.isBefore(plannedStart) ? Step.WAIT : Step.START;
    }

    static void check(final Boolean on, final Integer targetSoc, final Double powerKw, final Integer marginMinutes) {
        if (on == null || targetSoc == null || powerKw == null || marginMinutes == null) {
            throw EnergyException.invalidAutomation(
                "chargeBeforeOutage, targetSoc, chargePowerKw and marginMinutes are required");
        }
        if (targetSoc < LOWEST_TARGET || targetSoc > HIGHEST_TARGET) {
            throw EnergyException.invalidAutomation("targetSoc must be between " + LOWEST_TARGET + " and " + HIGHEST_TARGET);
        }
        if (powerKw < LOWEST_POWER || powerKw > HIGHEST_POWER) {
            throw EnergyException.invalidAutomation("chargePowerKw must be between 1 and 8");
        }
        if (marginMinutes < 0 || marginMinutes > LONGEST_MARGIN) {
            throw EnergyException.invalidAutomation("marginMinutes must be between 0 and " + LONGEST_MARGIN);
        }
    }
}

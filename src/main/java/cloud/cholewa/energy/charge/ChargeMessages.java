package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.database.BatteryChargeRunEntity;
import cloud.cholewa.energy.time.HouseTimes;

import java.util.Locale;
import java.util.Optional;

//The words of the infos of a charge (HAS-336), English as every alert of the services; Discord only - the manual charge is
//not told by SMS (the task)
final class ChargeMessages {

    private ChargeMessages() {
    }

    static String started(final BatteryChargeRunEntity run) {
        return "Battery charging from the grid started (" + run.source() + "): " + soc(run.socAtStart()) + " to "
            + run.targetSoc() + " %, window " + HouseTimes.hour(run.windowStart()) + "-"
            + HouseTimes.hour(run.windowEnd()) + ".";
    }

    static String notStarted(final BatteryChargeRunEntity run) {
        return "Battery charging from the grid not started (" + run.source() + "): " + reason(run) + ".";
    }

    static String ended(final BatteryChargeRunEntity run) {
        final String why = switch (run.endedBy()) {
            case ChargeRules.BY_TARGET -> "target reached";
            case ChargeRules.BY_WINDOW -> "window over";
            case ChargeRules.BY_CANCEL -> "cancelled";
            default -> reason(run);
        };
        return "Battery charging from the grid ended - " + why + " at " + soc(run.socAtEnd()) + " (started "
            + HouseTimes.hour(run.windowStart()) + " at " + soc(run.socAtStart())
            + "); the inverter set back to Self Use.";
    }

    private static String reason(final BatteryChargeRunEntity run) {
        return Optional.ofNullable(run.deliveryStatus())
            .map(status -> "the SolaX cloud answered status " + status)
            .orElse("the inverter did not take the command");
    }

    private static String soc(final Double soc) {
        return Optional.ofNullable(soc).map(value -> String.format(Locale.ROOT, "%.0f %%", value)).orElse("not known");
    }
}

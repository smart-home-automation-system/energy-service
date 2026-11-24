package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.database.EnergyReserveEntity;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

//The words of the notices of the battery reserve (HAS-494), English as every alert of the services; Discord only, never
//by SMS
final class ReserveMessages {

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private ReserveMessages() {
    }

    static String ended(final int level, final LocalDateTime until) {
        return "Battery reserve of " + level + " % ended (set until " + AT.format(until)
            + "); the inverter set back to the baseline Self Use.";
    }

    static String notWritten(final EnergyReserveEntity row, final String why) {
        return "The " + setting(row) + " was not written to the inverter: " + why
            + "; tried again every 10 minutes.";
    }

    static String written(final EnergyReserveEntity row) {
        return "The " + setting(row) + " is written to the inverter again.";
    }

    private static String setting(final EnergyReserveEntity row) {
        return row.enabled() ? "battery reserve of " + row.level() + " %" : "baseline Self Use (no battery reserve)";
    }
}

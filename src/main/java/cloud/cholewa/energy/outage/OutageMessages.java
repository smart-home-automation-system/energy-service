package cloud.cholewa.energy.outage;

import cloud.cholewa.energy.solax.SolaxReading;
import cloud.cholewa.energy.time.HouseTimes;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

//The words of the notifications of an outage (HAS-334), English as every alert of the services, short enough for one
//SMS with the level in front (notification-service adds "ERROR: "). The time left is the energy above the 10 % the
//SOLAX stops the EPS at, at the power the battery gives now - left out while it does not discharge
final class OutageMessages {

    static final double EPS_FLOOR_SOC = 10.0;
    private static final double DISCHARGING_BELOW_WATTS = -20.0;

    private OutageMessages() {
    }

    static String lost(final LocalDateTime startedAt, final SolaxReading reading, final double capacity) {
        return "Grid lost at " + HouseTimes.hour(startedAt) + " - the house runs on the battery: "
            + battery(reading, capacity) + ".";
    }

    static String stillLost(final LocalDateTime startedAt, final LocalDateTime now, final SolaxReading reading,
                            final double capacity) {
        return "Grid still lost since " + HouseTimes.hour(startedAt) + " (" + length(startedAt, now) + ") - battery "
            + battery(reading, capacity) + ".";
    }

    static String lowBattery(final LocalDateTime startedAt, final SolaxReading reading, final double capacity) {
        return "Battery low, the grid lost since " + HouseTimes.hour(startedAt) + ": " + battery(reading, capacity)
            + " - the house goes dark at 10 %.";
    }

    static String back(final LocalDateTime startedAt, final LocalDateTime endedAt, final SolaxReading reading) {
        return "Grid back at " + HouseTimes.hour(endedAt) + " after " + length(startedAt, endedAt) + " - battery "
            + soc(reading) + ".";
    }

    //"1 h 5 min", "25 min"
    static String length(final LocalDateTime from, final LocalDateTime to) {
        return HouseTimes.length(from, to);
    }

    //"64 %, about 3 h 10 min left at 2.1 kW"; "not known" without a reading
    static String battery(final SolaxReading reading, final double capacity) {
        return soc(reading) + timeLeft(reading, capacity).map(left -> ", " + left).orElse("");
    }

    static Optional<String> timeLeft(final SolaxReading reading, final double capacity) {
        return Optional.ofNullable(reading)
            .map(SolaxReading::battery)
            .filter(battery -> battery.soc() != null && battery.power() != null)
            .filter(battery -> battery.power() < DISCHARGING_BELOW_WATTS)
            .map(battery -> {
                final double kwh = Math.max(0, battery.soc() - EPS_FLOOR_SOC) / 100 * capacity;
                final double kw = -battery.power() / 1000;
                return "about " + HouseTimes.minutes(Math.round(kwh / kw * 60)) + " left at "
                    + String.format(Locale.ROOT, "%.1f", kw) + " kW";
            });
    }

    private static String soc(final SolaxReading reading) {
        return Optional.ofNullable(reading)
            .map(SolaxReading::battery)
            .map(SolaxReading.Battery::soc)
            .map(soc -> String.format(Locale.ROOT, "%.0f %%", soc))
            .orElse("not known");
    }
}

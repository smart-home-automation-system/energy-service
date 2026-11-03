package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.database.BatteryChargeRunEntity;
import cloud.cholewa.energy.error.EnergyException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;

//The rules of a charge of the battery (HAS-336), without I/O.
//The window starts at the minute of the request and ends at "until", or after the estimate: the energy from the SOC to the
//target of the usable capacity (energy.solax.battery-capacity) at the charge power, plus the margin - at least a
//minute, at most the longest window.
//A run ends by its target once the SOC reaches it, by its window once the window is over.
//The delivery of a command: 4 is the inverter carrying it out; 1 offline, 2 issuance failed, 5 failed and 6 timed out
//end the run; 3 (issued) and no entry wait until the delivery timeout
public final class ChargeRules {

    static final String DASHBOARD = "dashboard";
    static final String AUTOMATION = "automation";
    static final Set<String> SOURCES = Set.of(DASHBOARD, AUTOMATION);
    static final int LOWEST_TARGET = 10;
    static final int HIGHEST_TARGET = 100;
    static final int EXECUTING = 4;
    static final int OFFLINE = 1;
    static final String BY_TARGET = "target";
    static final String BY_WINDOW = "window";
    static final String BY_CANCEL = "cancel";
    static final String BY_FAILURE = "failed";

    private ChargeRules() {
    }

    record Window(LocalDateTime start, LocalDateTime end) {
    }

    static void check(final int targetSoc, final String source) {
        if (targetSoc < LOWEST_TARGET || targetSoc > HIGHEST_TARGET) {
            throw EnergyException.invalidCharge("targetSoc must be between " + LOWEST_TARGET + " and " + HIGHEST_TARGET);
        }
        if (!SOURCES.contains(source)) {
            throw EnergyException.invalidCharge("source must be dashboard or automation");
        }
    }

    //the window of a run asked at "now" - "until" must be after the start and within the longest window
    static Window window(final LocalDateTime now, final double soc, final int targetSoc, final LocalDateTime until,
                         final double capacityKwh, final ChargeProperties properties) {
        final LocalDateTime start = now.truncatedTo(ChronoUnit.MINUTES);
        final LocalDateTime end = Optional.ofNullable(until)
            .map(asked -> asked.truncatedTo(ChronoUnit.MINUTES))
            .orElseGet(() -> start.plus(estimate(soc, targetSoc, capacityKwh, properties)));
        if (!end.isAfter(start) || end.isAfter(start.plus(properties.longest()))) {
            throw EnergyException.invalidCharge("until must be after now and at most "
                + properties.longest().toHours() + " h ahead");
        }
        return new Window(start, end);
    }

    //the time to the target at the charge power plus the margin, whole minutes, at most the longest window
    static Duration estimate(final double soc, final int targetSoc, final double capacityKwh,
                             final ChargeProperties properties) {
        final double kwh = Math.max(0, targetSoc - soc) / 100 * capacityKwh;
        final long minutes = (long) Math.ceil(kwh / properties.powerKw() * 60);
        final Duration estimate = Duration.ofMinutes(Math.max(1, minutes)).plus(properties.margin());
        return estimate.compareTo(properties.longest()) > 0 ? properties.longest() : estimate;
    }

    //why a run carried out by the inverter ends now: its target reached, or its window over
    static Optional<String> endOf(final BatteryChargeRunEntity run, final Double soc, final LocalDateTime now) {
        if (soc != null && soc >= run.targetSoc()) {
            return Optional.of(BY_TARGET);
        }
        return now.isBefore(run.windowEnd()) ? Optional.empty() : Optional.of(BY_WINDOW);
    }

    //where a run is: its command not yet carried out, charging, ended without its baseline set back, or over
    enum Stage { REQUESTED, CHARGING, ENDING, OVER }

    static Stage stageOf(final BatteryChargeRunEntity run) {
        if (run.open()) {
            return run.deliveredAt() == null ? Stage.REQUESTED : Stage.CHARGING;
        }
        return run.restorePending() ? Stage.ENDING : Stage.OVER;
    }

    //what a status of the command means: the inverter carries it out; it certainly did not take it (offline, issuance
    //failed, execution failed); it may have taken it (timed out, or no execution within the delivery timeout); not yet
    enum Delivery { EXECUTING, OFFLINE, NOT_TAKEN, UNCERTAIN, WAITING }

    //the battery and the grid now, null what is not known or not fresh
    public record Power(Double soc, Double battery, Double grid) {

        static final Power NOT_KNOWN = new Power(null, null, null);

        //the battery charging and the house taking from the grid at the given power or more - the grid charge going on
        boolean gridCharging(final int watts) {
            return battery != null && grid != null && battery >= watts && grid >= watts;
        }
    }

    static Delivery deliveryOf(final Integer status, final boolean late) {
        return deliveryOf(status, late, Power.NOT_KNOWN, Integer.MAX_VALUE);
    }

    //the status of the command when the cloud answers it, else the battery charging from the grid (owner, 2026-11-03)
    static Delivery deliveryOf(final Integer status, final boolean late, final Power power, final int chargingAboveWatts) {
        if ((status != null && status == EXECUTING) || power.gridCharging(chargingAboveWatts)) {
            return Delivery.EXECUTING;
        }
        if (status != null && status == OFFLINE) {
            return Delivery.OFFLINE;
        }
        if (status != null && (status == 2 || status == 5)) {
            return Delivery.NOT_TAKEN;
        }
        return (status != null && status == 6) || late ? Delivery.UNCERTAIN : Delivery.WAITING;
    }

    //the minutes left to the target at the charge power, empty without a SOC
    static Optional<Long> minutesLeft(final Double soc, final int targetSoc, final double capacityKwh,
                                      final ChargeProperties properties) {
        return Optional.ofNullable(soc)
            .map(now -> Math.max(0, targetSoc - now) / 100 * capacityKwh / properties.powerKw() * 60)
            .map(Math::round);
    }
}

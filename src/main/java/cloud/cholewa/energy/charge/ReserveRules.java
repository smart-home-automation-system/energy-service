package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.error.EnergyException;

import java.time.Duration;
import java.time.LocalDateTime;

//The rules of the battery reserve (HAS-494), without I/O - copied by the dashboard (HAS-496), so a rule changed here is
//changed there too. The switch and the level are required; the level 10 - 100 % by 5; until only for a reserve switched
//on, after now and at most 7 days ahead (INVALID_RESERVE with the reason)
public final class ReserveRules {

    static final int LOWEST_LEVEL = 10;
    static final int HIGHEST_LEVEL = 100;
    static final int LEVEL_STEP = 5;
    static final Duration LONGEST_UNTIL = Duration.ofDays(7);
    //a write the cloud refused or the inverter did not carry out is tried again after this long
    static final Duration RETRY_AFTER = Duration.ofMinutes(10);

    private ReserveRules() {
    }

    static void check(final Boolean enabled, final Integer level, final LocalDateTime until, final LocalDateTime now) {
        if (enabled == null || level == null) {
            throw EnergyException.invalidReserve("enabled and level are required");
        }
        if (level < LOWEST_LEVEL || level > HIGHEST_LEVEL || level % LEVEL_STEP != 0) {
            throw EnergyException.invalidReserve("level must be between " + LOWEST_LEVEL + " and " + HIGHEST_LEVEL
                + " in steps of " + LEVEL_STEP);
        }
        if (until == null) {
            return;
        }
        if (!enabled) {
            throw EnergyException.invalidReserve("until is only for a reserve switched on");
        }
        if (!until.isAfter(now) || until.isAfter(now.plus(LONGEST_UNTIL))) {
            throw EnergyException.invalidReserve("until must be after now and at most " + LONGEST_UNTIL.toDays()
                + " days ahead");
        }
    }

    //whether the reserve holds at this time: switched on and its until not reached
    static boolean inForce(final boolean enabled, final LocalDateTime until, final LocalDateTime now) {
        return enabled && (until == null || now.isBefore(until));
    }
}

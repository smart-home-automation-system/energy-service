package cloud.cholewa.energy.charge;

import java.time.LocalTime;
import java.util.Optional;

//The standing work mode of the inverter (HAS-494): the baseline of the owner in Self Use, or the battery reserve held by
//its floor in Back Up.
//With a reserve the floor and the grid charge limit are the level, the grid charges all day (00:00 - 23:59) - so the
//battery is topped up to the reserve whenever it falls below it, and the panels charge it above. The discharge window
//stays the one of the baseline. A charge of a run keeps the floor of this base
record SelfUseBase(ChargeProperties.Baseline baseline, Integer reserve) {

    static SelfUseBase of(final ChargeProperties.Baseline baseline) {
        return new SelfUseBase(baseline, null);
    }

    Optional<Integer> reserveLevel() {
        return Optional.ofNullable(reserve);
    }

    int minSoc() {
        return reserveLevel().orElse(baseline.minSoc());
    }

    boolean chargeFromGrid() {
        return reserve != null || Boolean.TRUE.equals(baseline.chargeFromGrid());
    }

    int chargeUpperSoc() {
        return reserveLevel().orElse(baseline.chargeUpperSoc());
    }

    boolean chargeAllDay() {
        return reserve != null;
    }

    LocalTime dischargeStart() {
        return baseline.dischargeStart();
    }

    LocalTime dischargeEnd() {
        return baseline.dischargeEnd();
    }
}

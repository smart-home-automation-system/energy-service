package cloud.cholewa.energy.outage;

import cloud.cholewa.energy.database.GridOutageEntity;
import cloud.cholewa.energy.database.MeterUptimeEntity;

import java.time.LocalDateTime;

//Decides without I/O what a start of the grid meter means (HAS-505). The meter loses its power with the grid, so a start
//after the time it was last seen is an outage between the two - one the live watch of HAS-334 could not see, the house
//down with it. A restart of the meter itself (a firmware update, a reboot, a crash) is no outage: only a reset reason of
//power-on decides; another one is a restart, none at all not conclusive - nothing is stored for either
final class MeterRestartRules {

    private MeterRestartRules() {
    }

    enum Kind { NONE, OUTAGE, RESTART, NOT_CONCLUSIVE }

    //from / to - the bounds of the outage: the meter last seen, the meter started again
    record Verdict(Kind kind, LocalDateTime from, LocalDateTime to, Integer resetReason) {

        static final Verdict NONE = new Verdict(Kind.NONE, null, null, null);
    }

    //stored - what the last answer said (seen null before the first one); bootedAt - when the meter started, by the
    //answer now
    static Verdict judge(final MeterUptimeEntity stored, final LocalDateTime bootedAt, final Integer resetReason) {
        if (stored == null || stored.seenAt() == null || !bootedAt.isAfter(stored.seenAt())) {
            return Verdict.NONE;
        }
        if (resetReason == null) {
            return new Verdict(Kind.NOT_CONCLUSIVE, stored.seenAt(), bootedAt, null);
        }
        final Kind kind = resetReason == MeterSys.POWER_ON ? Kind.OUTAGE : Kind.RESTART;
        return new Verdict(kind, stored.seenAt(), bootedAt, resetReason);
    }

    //whether the live watch holds an outage on [from, to] it may not have stored yet - the open one, or a row the
    //database did not take
    static boolean heldLive(final GridWatch watch, final LocalDateTime from, final LocalDateTime to) {
        return watch != null && (overlaps(watch.open(), from, to) || overlaps(watch.unsaved(), from, to));
    }

    private static boolean overlaps(final GridOutageEntity row, final LocalDateTime from, final LocalDateTime to) {
        return row != null && !row.startedAt().isAfter(to) && (row.endedAt() == null || !row.endedAt().isBefore(from));
    }
}

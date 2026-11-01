package cloud.cholewa.energy.outage;

import cloud.cholewa.energy.database.GridOutageEntity;
import cloud.cholewa.energy.solax.SolaxReading;
import cloud.cholewa.energy.solax.SolaxState;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

//The rules of an outage of the grid (HAS-334), without I/O: from what the service holds and the state of the SOLAX
//now, what it holds next, the row to store and the notifications to send.
//- A new reading of the SOLAX counts once (its house time); two in a row with the same signal decide.
//- Two LOST with no open outage: the outage opens at the first of them - an alert marked for an SMS.
//- Two PRESENT with an open outage: it closes at the first of them - an info marked for an SMS; with none, the grid
//  is present since the first of them.
//- While it lasts: the lowest SOC is kept; below energy.outage.low-soc one alert more, marked for an SMS, once an
//  outage (none when it was low at the loss already - the alert of the loss tells it); a reminder every energy.outage.reminder-every with the SOC and the time left, not by SMS.
//A reading that came before the open outage was read after a start counts like any other
final class OutageRules {

    static final int CONFIRMING_READS = 2;

    private OutageRules() {
    }

    enum Kind { ALERT_SMS, REMINDER, INFO_SMS }

    record Notice(Kind kind, String text) {
    }

    //next - what to hold once the row is stored; row - null when nothing changed in the database
    record Decision(GridWatch next, GridOutageEntity row, List<Notice> notices) {
    }

    static Decision decide(
        final GridWatch watch,
        final SolaxState solax,
        final LocalDateTime now,
        final OutageProperties properties,
        final double capacity
    ) {
        final List<Notice> notices = new ArrayList<>();
        GridWatch next = watch;
        final SolaxReading reading = solax.reading();
        final boolean newReading = reading != null && solax.readingAt() != null
            && (watch.lastReadingAt() == null || solax.readingAt().isAfter(watch.lastReadingAt()));

        if (newReading) {
            next = next.withReading(solax.readingAt(), GridSignal.of(reading));
            next = reading(next, reading, now, properties, capacity, notices);
        }
        next = remind(next, reading, now, properties, capacity, notices);

        //the row as it is to be stored - null when it did not change. An outage just closed is still held, with its
        //end: GridOutageWatch drops it once stored
        final GridOutageEntity row = Objects.equals(watch.open(), next.open()) ? null : next.open();
        return new Decision(next, row, List.copyOf(notices));
    }

    private static GridWatch reading(
        final GridWatch watch,
        final SolaxReading reading,
        final LocalDateTime now,
        final OutageProperties properties,
        final double capacity,
        final List<Notice> notices
    ) {
        final Double soc = reading.battery() == null ? null : reading.battery().soc();
        final GridOutageEntity open = watch.open();
        if (open == null) {
            if (watch.confirmed(GridSignal.LOST)) {
                notices.add(new Notice(Kind.ALERT_SMS,
                    OutageMessages.lost(watch.streakSince(), reading, capacity)));
                final GridOutageEntity opened = GridOutageEntity.opened(watch.streakSince(), soc, now);
                //the alert of the loss tells the low battery already - no second SMS for it
                return watch.withOpen(low(soc, properties) ? opened.lowSocAlerted(now) : opened);
            }
            if (watch.confirmed(GridSignal.PRESENT) && watch.presentSince() == null) {
                return watch.presentSince(watch.streakSince());
            }
            return watch;
        }
        if (watch.confirmed(GridSignal.PRESENT)) {
            notices.add(new Notice(Kind.INFO_SMS, OutageMessages.back(open.startedAt(), watch.streakSince(), reading)));
            return watch.withOpen(open.closed(watch.streakSince(), soc));
        }
        GridOutageEntity updated = open.withSoc(soc);
        if (low(soc, properties) && open.lowSocAlertedAt() == null) {
            notices.add(new Notice(Kind.ALERT_SMS, OutageMessages.lowBattery(open.startedAt(), reading, capacity)));
            updated = updated.lowSocAlerted(now);
        }
        return watch.withOpen(updated);
    }

    private static boolean low(final Double soc, final OutageProperties properties) {
        return soc != null && soc < properties.lowSoc();
    }

    private static GridWatch remind(
        final GridWatch watch,
        final SolaxReading reading,
        final LocalDateTime now,
        final OutageProperties properties,
        final double capacity,
        final List<Notice> notices
    ) {
        final GridOutageEntity open = watch.open();
        if (open == null || open.endedAt() != null
            || now.isBefore(open.lastRemindedAt().plus(properties.reminderEvery()))) {
            return watch;
        }
        notices.add(new Notice(Kind.REMINDER, OutageMessages.stillLost(open.startedAt(), now, reading, capacity)));
        return watch.withOpen(open.reminded(now));
    }
}

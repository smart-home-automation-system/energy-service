package cloud.cholewa.energy.monitor;

import cloud.cholewa.energy.database.SourceAlertEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

//What the monitor of the sources decides (HAS-332), without I/O - pinned by SourceRulesTest.
//Silence, the pattern of blinds and light: a source out for its bound opens an episode at the time it was last heard
//and is one alert a pass naming every such source; a reminder every reminderEvery while it stays out; the return -
//heard after the episode opened, never only not known - an info, the episode closed at that reading. An alert that did not get through is tried again with
//every pass, and one that never did is named so by the return.
//Overload: a phase above phaseCurrentWarn in every pass for overloadAfter opens an episode at the first of them and is
//one warning, never repeated; below in every pass for overloadAfter ends it with an info. A current not known (the
//meter stale) breaks both counts.
//The opening of an episode is held at once; what a notice changes (told, closed) only once it was published - the
//monitor applies the rows of a notice it published
final class SourceRules {

    private SourceRules() {
    }

    record Decision(SourceWatch next, List<Notice> notices) {
    }

    //rows - the episodes as they are once the notice was published
    record Notice(Type type, String text, List<Row> rows) {
    }

    record Row(Source source, SourceAlertEntity entity) {
    }

    enum Type {
        ALERT, REMINDER, INFO, OVERLOAD, OVERLOAD_END
    }

    static Decision decide(final SourceWatch held, final Map<Source, Silence> silences,
                           final Map<Source, Double> currents, final LocalDateTime now,
                           final AvailabilityProperties availability, final OverloadProperties overload) {
        SourceWatch next = held;
        final List<Row> alerts = new ArrayList<>();
        final List<Row> reminders = new ArrayList<>();
        final List<Row> returns = new ArrayList<>();
        final List<Notice> notices = new ArrayList<>();

        for (final Map.Entry<Source, Silence> entry : silences.entrySet()) {
            final Source source = entry.getKey();
            final Silence silence = entry.getValue();
            final SourceAlertEntity open = next.open().get(source);
            if (silence.status() == Silence.Status.OUT && open == null) {
                final SourceAlertEntity opened = opened(source, silence.heardAt());
                next = next.with(opened, source);
                alerts.add(new Row(source, told(opened, now)));
            } else if (silence.status() == Silence.Status.OUT && open.lastToldAt() == null) {
                alerts.add(new Row(source, told(open, now)));
            } else if (silence.status() == Silence.Status.OUT
                && !open.lastToldAt().plus(availability.reminderEvery()).isAfter(now)) {
                reminders.add(new Row(source, told(open, now)));
            } else if (silence.status() == Silence.Status.HEARD && open != null
                && silence.heardAt().isAfter(open.openedAt())) {
                returns.add(new Row(source, closed(open, silence.heardAt())));
            }
        }
        if (!returns.isEmpty()) {
            notices.add(new Notice(Type.INFO, SourceMessages.back(returns), returns));
        }
        if (!alerts.isEmpty()) {
            notices.add(new Notice(Type.ALERT, SourceMessages.silent(alerts, now), alerts));
        }
        if (!reminders.isEmpty()) {
            notices.add(new Notice(Type.REMINDER, SourceMessages.stillSilent(reminders, now), reminders));
        }

        for (final Source phase : List.of(Source.PHASE_A, Source.PHASE_B, Source.PHASE_C)) {
            final Phase decided = phase(next, phase, currents.get(phase), now, overload);
            next = decided.next();
            notices.addAll(decided.notices());
        }
        return new Decision(next, List.copyOf(notices));
    }

    private record Phase(SourceWatch next, List<Notice> notices) {
    }

    private static Phase phase(final SourceWatch held, final Source phase, final Double current,
                               final LocalDateTime now, final OverloadProperties overload) {
        if (current == null) {
            return new Phase(held.withAbove(phase, null).withBelow(phase, null), List.of());
        }
        final boolean above = current > overload.phaseCurrentWarn();
        final SourceAlertEntity open = held.open().get(phase);
        if (open == null) {
            final LocalDateTime since = held.aboveSince().getOrDefault(phase, now);
            final SourceWatch counted = held.withBelow(phase, null).withAbove(phase, above ? since : null);
            if (!above || since.plus(overload.overloadAfter()).isAfter(now)) {
                return new Phase(counted, List.of());
            }
            final SourceAlertEntity opened = opened(phase, since);
            return new Phase(counted.withAbove(phase, null).with(opened, phase), List.of(overloaded(phase, opened,
                current, now, overload)));
        }
        if (above) {
            final SourceWatch counted = held.withBelow(phase, null);
            return new Phase(counted, open.lastToldAt() == null
                ? List.of(overloaded(phase, open, current, now, overload)) : List.of());
        }
        final LocalDateTime since = held.belowSince().getOrDefault(phase, now);
        final SourceWatch counted = held.withBelow(phase, since);
        if (since.plus(overload.overloadAfter()).isAfter(now)) {
            return new Phase(counted, List.of());
        }
        final SourceAlertEntity closed = closed(open, since);
        return new Phase(counted, List.of(new Notice(Type.OVERLOAD_END,
            SourceMessages.overloadOver(phase, closed, current, overload.phaseCurrentWarn()),
            List.of(new Row(phase, closed)))));
    }

    private static Notice overloaded(final Source phase, final SourceAlertEntity open, final double current,
                                     final LocalDateTime now, final OverloadProperties overload) {
        return new Notice(Type.OVERLOAD, SourceMessages.overloaded(phase, open, current, now,
            overload.phaseCurrentWarn()), List.of(new Row(phase, told(open, now))));
    }

    private static SourceAlertEntity opened(final Source source, final LocalDateTime since) {
        return new SourceAlertEntity(null, source.wireName(), source.kind().wireName(), since, null, null);
    }

    private static SourceAlertEntity told(final SourceAlertEntity row, final LocalDateTime at) {
        return new SourceAlertEntity(row.id(), row.source(), row.kind(), row.openedAt(), at, row.closedAt());
    }

    private static SourceAlertEntity closed(final SourceAlertEntity row, final LocalDateTime at) {
        return new SourceAlertEntity(row.id(), row.source(), row.kind(), row.openedAt(), row.lastToldAt(), at);
    }
}

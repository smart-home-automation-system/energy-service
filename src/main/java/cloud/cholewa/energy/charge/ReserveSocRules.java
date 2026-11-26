package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.time.HouseTimes;

import java.time.LocalDateTime;
import java.util.Optional;

//Whether the battery itself is held at the reserve (HAS-495), without I/O - pinned by ReserveSocRulesTest. The settings
//read from the dongle (ReserveFloorRules, HAS-518) say what the inverter was told; the state of charge says whether it
//does it - a floor the inverter ignores, or settings that are not known while the battery runs down. What a pass sees:
//- OFF - no reserve holds: an open episode ends with an info.
//- NOT_JUDGED - the grid is out (in EPS the drop is the purpose), a charge is open or being ended, the last write of the
//  reserve is not sent or taken (the reserve tells that itself), the state of charge is not known, or the battery is
//  below the reserve but charging (switched on below its level, the inverter brings it up from the grid): the count
//  starts anew, an open episode stays open and is not reminded.
//- FINE - the battery at the level less the tolerance or above: an open episode ends with an info.
//- LOW - below it and not charging: in every pass for warnAfter opens an episode with a warning (opened at the first of
//  them), then a reminder every reminderEvery from the last notice
final class ReserveSocRules {

    static final String SOURCE = "battery";
    static final String KIND = "reserve";
    private static final String HOW = " Check the work mode and the Min SoC of the SOLAX in the SolaX application or "
        + "SolaxCloud.";

    private ReserveSocRules() {
    }

    enum View { OFF, NOT_JUDGED, FINE, LOW }

    //level - of the reserve, %; soc - of the battery, %, null unless FINE or LOW
    record Seen(View view, Integer level, Double soc) {

        static Seen of(final View view) {
            return new Seen(view, null, null);
        }
    }

    enum Notice { WARNING, REMINDER, INFO }

    //since - the first LOW pass of an episode not opened yet, held for the next pass; row - what to store once the notice
    //was published; notice and text - what to publish, empty when nothing is told
    record Outcome(LocalDateTime since, Optional<SourceAlertEntity> row, Optional<Notice> notice, String text) {

        static Outcome quiet(final LocalDateTime since) {
            return new Outcome(since, Optional.empty(), Optional.empty(), null);
        }
    }

    //inForce - the reserve holds. judged - the grid present, no charge open or being ended and the last write of the
    //reserve sent or taken. soc - of the battery while fresh. charging - the battery takes power now
    static Seen see(final boolean inForce, final Integer level, final boolean judged, final Optional<Double> soc,
                    final boolean charging, final int tolerance) {
        if (!inForce) {
            return Seen.of(View.OFF);
        }
        if (!judged || soc.isEmpty()) {
            return Seen.of(View.NOT_JUDGED);
        }
        if (!below(soc.get(), level, tolerance)) {
            return new Seen(View.FINE, level, soc.get());
        }
        return charging ? Seen.of(View.NOT_JUDGED) : new Seen(View.LOW, level, soc.get());
    }

    //held of GET /battery/reserve: left out (null) without a reserve in force or while the state of charge is not known
    static Boolean held(final boolean inForce, final Integer level, final Optional<Double> soc, final int tolerance) {
        if (!inForce) {
            return null;
        }
        return soc.map(known -> !below(known, level, tolerance)).orElse(null);
    }

    private static boolean below(final double soc, final int level, final int tolerance) {
        return soc < level - tolerance;
    }

    static Outcome decide(final Seen seen, final Optional<SourceAlertEntity> open, final LocalDateTime since,
                          final ReserveSocProperties properties, final LocalDateTime now) {
        return switch (seen.view()) {
            case NOT_JUDGED -> Outcome.quiet(null);
            case OFF -> open.map(row -> closed(row, now, "The battery reserve is switched off - the warning of the "
                + "battery below it, open since " + HouseTimes.hour(row.openedAt()) + ", ends."))
                .orElse(Outcome.quiet(null));
            case FINE -> open.map(row -> closed(row, now, "The battery is held at the reserve of " + seen.level()
                + " % again: " + percent(seen.soc()) + " % - below it from " + HouseTimes.hour(row.openedAt()) + " to "
                + HouseTimes.hour(now) + " (" + HouseTimes.length(row.openedAt(), now) + ")."))
                .orElse(Outcome.quiet(null));
            case LOW -> open.map(row -> reminded(seen, row, properties, now))
                .orElseGet(() -> counted(seen, since, properties, now));
        };
    }

    private static Outcome counted(final Seen seen, final LocalDateTime since, final ReserveSocProperties properties,
                                   final LocalDateTime now) {
        final LocalDateTime first = since == null ? now : since;
        if (HouseTimes.between(first, now).compareTo(properties.warnAfter()) < 0) {
            return Outcome.quiet(first);
        }
        return new Outcome(first, Optional.of(new SourceAlertEntity(null, SOURCE, KIND, first, now, null, null)),
            Optional.of(Notice.WARNING), what(seen, first) + HOW);
    }

    private static Outcome reminded(final Seen seen, final SourceAlertEntity open,
                                    final ReserveSocProperties properties, final LocalDateTime now) {
        final boolean due = open.lastToldAt() == null
            || !now.isBefore(open.lastToldAt().plus(properties.reminderEvery()));
        if (!due) {
            return Outcome.quiet(null);
        }
        return new Outcome(null, Optional.of(new SourceAlertEntity(open.id(), open.source(), open.kind(),
            open.openedAt(), now, null, null)), Optional.of(Notice.REMINDER),
            "Still: " + what(seen, open.openedAt()) + HOW);
    }

    private static Outcome closed(final SourceAlertEntity open, final LocalDateTime now, final String text) {
        return new Outcome(null, Optional.of(new SourceAlertEntity(open.id(), open.source(), open.kind(),
            open.openedAt(), open.lastToldAt(), now, null)), Optional.of(Notice.INFO), text);
    }

    private static String what(final Seen seen, final LocalDateTime since) {
        return "The battery reserve of " + seen.level() + " % is not held: the battery is at " + percent(seen.soc())
            + " % and not charging, below the reserve since " + HouseTimes.hour(since) + ".";
    }

    private static long percent(final double soc) {
        return Math.round(soc);
    }
}

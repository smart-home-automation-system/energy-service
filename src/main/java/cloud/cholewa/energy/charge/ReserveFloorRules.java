package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.solax.SolaxSettings;
import cloud.cholewa.energy.solax.WorkMode;
import cloud.cholewa.energy.time.HouseTimes;

import java.time.LocalDateTime;
import java.util.Optional;

//Whether the inverter holds the battery reserve (HAS-518), without I/O - pinned by ReserveFloorRulesTest. The service
//writes the reserve as the Back Up work mode, but the write sets Charge Battery To only: the inverter pulls its Min SoC
//down to it and never up (proven 2026-11-25), so a reserve raised - or lowered and raised again - is held only down to
//the old, lower floor until the owner sets the Min SoC by hand. And a work mode changed outside the service holds no
//reserve at all. What a pass sees:
//- OFF - no reserve holds: an open episode ends with an info.
//- NOT_JUDGED - the settings of the inverter are not known, or the standing work mode is not the one in the inverter
//  for now (a write pending or failed - the reserve tells that itself -, a charge going on or being ended): the count
//  starts anew, an open episode stays open and is not reminded.
//- FINE - Back Up with its Min SoC at the level or above: an open episode ends with an info.
//- WRONG - not in Back Up, or its Min SoC below the level: in every pass for warnAfter opens an episode with a
//  warning (opened at the first of them), then a reminder every reminderEvery from the last notice
final class ReserveFloorRules {

    static final String SOURCE = "solax";
    static final String KIND = "reserve";

    private ReserveFloorRules() {
    }

    enum View { OFF, NOT_JUDGED, FINE, WRONG }

    //level - of the reserve, %; settings - of the inverter, null unless FINE or WRONG
    record Seen(View view, Integer level, SolaxSettings settings) {

        static Seen of(final View view) {
            return new Seen(view, null, null);
        }
    }

    enum Notice { WARNING, REMINDER, INFO }

    //since - the first WRONG pass of an episode not opened yet, held for the next pass; row - what to store once the
    //notice was published; notice and text - what to publish, empty when nothing is told
    record Outcome(LocalDateTime since, Optional<SourceAlertEntity> row, Optional<Notice> notice, String text) {

        static Outcome quiet(final LocalDateTime since) {
            return new Outcome(since, Optional.empty(), Optional.empty(), null);
        }
    }

    //inForce - the reserve holds. settled - its last write was sent or taken and no charge is open or being ended.
    //settings - of the inverter while fresh
    static Seen see(final boolean inForce, final Integer level, final boolean settled,
                    final Optional<SolaxSettings> settings) {
        if (!inForce) {
            return Seen.of(View.OFF);
        }
        if (!settled || settings.isEmpty()) {
            return Seen.of(View.NOT_JUDGED);
        }
        final SolaxSettings read = settings.get();
        if (read.workMode() != WorkMode.BACK_UP) {
            return new Seen(View.WRONG, level, read);
        }
        if (read.backUpMinSoc() == null) {
            return Seen.of(View.NOT_JUDGED);
        }
        return new Seen(read.backUpMinSoc() < level ? View.WRONG : View.FINE, level, read);
    }

    //floorBelowLevel of GET /battery/reserve: false without a reserve, null while the Min SoC is not known
    static Boolean floorBelowLevel(final boolean inForce, final Integer level, final Optional<SolaxSettings> settings) {
        if (!inForce) {
            return false;
        }
        return settings.map(SolaxSettings::backUpMinSoc).map(minSoc -> minSoc < level).orElse(null);
    }

    static Outcome decide(final Seen seen, final Optional<SourceAlertEntity> open, final LocalDateTime since,
                          final ReserveFloorProperties properties, final LocalDateTime now) {
        return switch (seen.view()) {
            case NOT_JUDGED -> Outcome.quiet(null);
            case OFF -> open.map(row -> closed(row, now, "The battery reserve is switched off - the warning of its "
                + "floor, open since " + HouseTimes.hour(row.openedAt()) + ", ends.")).orElse(Outcome.quiet(null));
            case FINE -> open.map(row -> closed(row, now, "The SOLAX holds the battery reserve of " + seen.level()
                + " % again: Back Up with Min SoC " + seen.settings().backUpMinSoc() + " % - not held from "
                + HouseTimes.hour(row.openedAt()) + " to " + HouseTimes.hour(now) + " ("
                + HouseTimes.length(row.openedAt(), now) + ").")).orElse(Outcome.quiet(null));
            case WRONG -> open.map(row -> reminded(seen, row, properties, now))
                .orElseGet(() -> counted(seen, since, properties, now));
        };
    }

    private static Outcome counted(final Seen seen, final LocalDateTime since, final ReserveFloorProperties properties,
                                   final LocalDateTime now) {
        final LocalDateTime first = since == null ? now : since;
        if (HouseTimes.between(first, now).compareTo(properties.warnAfter()) < 0) {
            return Outcome.quiet(first);
        }
        return new Outcome(first, Optional.of(new SourceAlertEntity(null, SOURCE, KIND, first, now, null, null)),
            Optional.of(Notice.WARNING), what(seen) + " since " + HouseTimes.hour(first) + ". " + how(seen));
    }

    private static Outcome reminded(final Seen seen, final SourceAlertEntity open,
                                    final ReserveFloorProperties properties, final LocalDateTime now) {
        final boolean due = open.lastToldAt() == null
            || !now.isBefore(open.lastToldAt().plus(properties.reminderEvery()));
        if (!due) {
            return Outcome.quiet(null);
        }
        return new Outcome(null, Optional.of(new SourceAlertEntity(open.id(), open.source(), open.kind(),
            open.openedAt(), now, null, null)), Optional.of(Notice.REMINDER),
            "Still: " + what(seen) + " since " + HouseTimes.hour(open.openedAt()) + ". " + how(seen));
    }

    private static Outcome closed(final SourceAlertEntity open, final LocalDateTime now, final String text) {
        return new Outcome(null, Optional.of(new SourceAlertEntity(open.id(), open.source(), open.kind(),
            open.openedAt(), open.lastToldAt(), now, null)), Optional.of(Notice.INFO), text);
    }

    private static String what(final Seen seen) {
        final SolaxSettings settings = seen.settings();
        return settings.workMode() == WorkMode.BACK_UP
            ? "The battery reserve of " + seen.level() + " % is held only down to " + settings.backUpMinSoc()
            + " %: the Min SoC of the Back Up mode of the SOLAX is " + settings.backUpMinSoc() + " %"
            : "The battery reserve of " + seen.level() + " % is not held: the work mode of the SOLAX is "
            + settings.workMode().wireName() + " (code " + settings.workModeCode() + "), not Back Up";
    }

    private static String how(final Seen seen) {
        return seen.settings().workMode() == WorkMode.BACK_UP
            ? "The inverter never raises it by itself and the service cannot: set Min SoC to " + seen.level()
            + " % by hand in the SolaX application or SolaxCloud."
            : "Set the work mode back to Back Up in the SolaX application or SolaxCloud, or switch the reserve off and "
            + "on in the dashboard.";
    }
}

package cloud.cholewa.energy.production;

import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.time.HouseTimes;

import java.time.LocalDateTime;
import java.util.Optional;

//What the production of an inverter in the sun is to the household (HAS-333), without I/O - pinned by ProductionRulesTest.
//The sun shines when it is above the horizon and the illuminance of the station, known and not stale, is at the
//threshold or above; an input not known never holds. An inverter is judged when its reading is fresh and the SOLAX is not
//in EPS (the outage alert covers a lost grid). Low - a judged inverter giving less than minPower while the sun shines.
//- No open alert: low in every pass for silence opens one (an alert naming the inverter, the illuminance and the power,
//  opened at the first low pass); a pass not low - the sun gone, a value not known, the power back - starts the count anew.
//- An open alert: closed by a judged reading at minPower or above, with an info, sun or not; a reminder every
//  reminderEvery from the last notice, only in a low pass (never at night)
final class ProductionRules {

    static final String KIND = "production";

    private ProductionRules() {
    }

    //a judged inverter: the source name (solax, sofar), the words of the messages, the PV power, W
    record Inverter(String source, String words, double pvPower) {
    }

    //what a pass sees of the sky: the illuminance, lx, null when not known
    record Sky(boolean sunUp, Double lux) {

        boolean shines(final int threshold) {
            return sunUp && lux != null && lux >= threshold;
        }
    }

    enum Notice { ALERT, REMINDER, INFO }

    //lowSince - the start of the low passes, kept by the caller in memory (null when not low); row - what to store once
    //the notice was published; notice and text - what to publish, empty when nothing is told
    record Outcome(LocalDateTime lowSince, Optional<SourceAlertEntity> row, Optional<Notice> notice, String text) {
    }

    static Outcome decide(final Inverter inverter, final Sky sky, final Optional<SourceAlertEntity> open,
                          final LocalDateTime lowSince, final ProductionProperties properties, final LocalDateTime now) {
        final boolean low = sky.shines(properties.illuminanceThreshold()) && inverter.pvPower() < properties.minPower();
        if (open.isPresent()) {
            return withOpen(inverter, sky, open.get(), low, properties, now);
        }
        if (!low) {
            return new Outcome(null, Optional.empty(), Optional.empty(), null);
        }
        final LocalDateTime since = lowSince == null ? now : lowSince;
        if (HouseTimes.between(since, now).compareTo(properties.silence()) < 0) {
            return new Outcome(since, Optional.empty(), Optional.empty(), null);
        }
        return new Outcome(since, Optional.of(new SourceAlertEntity(null, inverter.source(), KIND, since, now, null, null)),
            Optional.of(Notice.ALERT), inverter.words() + " produces nothing in the sun: " + watts(inverter.pvPower())
                + " at " + lux(sky.lux()) + " since " + HouseTimes.hour(since) + ".");
    }

    private static Outcome withOpen(final Inverter inverter, final Sky sky, final SourceAlertEntity open,
                                    final boolean low, final ProductionProperties properties, final LocalDateTime now) {
        if (inverter.pvPower() >= properties.minPower()) {
            return new Outcome(null, Optional.of(new SourceAlertEntity(open.id(), open.source(), open.kind(),
                open.openedAt(), open.lastToldAt(), now, null)), Optional.of(Notice.INFO),
                inverter.words() + " produces again: " + watts(inverter.pvPower()) + " - from "
                    + HouseTimes.hour(open.openedAt()) + " to " + HouseTimes.hour(now) + " ("
                    + HouseTimes.length(open.openedAt(), now) + ").");
        }
        final boolean due = open.lastToldAt() == null
            || !now.isBefore(open.lastToldAt().plus(properties.reminderEvery()));
        if (low && due) {
            return new Outcome(null, Optional.of(new SourceAlertEntity(open.id(), open.source(), open.kind(),
                open.openedAt(), now, null, null)), Optional.of(Notice.REMINDER),
                inverter.words() + " still produces nothing in the sun: " + watts(inverter.pvPower()) + " at "
                    + lux(sky.lux()) + " since " + HouseTimes.hour(open.openedAt()) + ".");
        }
        return new Outcome(null, Optional.empty(), Optional.empty(), null);
    }

    private static String watts(final double power) {
        return Math.round(power) + " W";
    }

    private static String lux(final Double lux) {
        return Math.round(lux) + " lx";
    }
}

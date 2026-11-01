package cloud.cholewa.energy.monitor;

import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.time.HouseTimes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

//The words of the notifications of the sources (HAS-332), English as every alert of the services; the look is the
//owner's call, made on a real message
final class SourceMessages {

    private SourceMessages() {
    }

    //"Not available: the SOLAX dongle does not answer since 10:05 (6 min)."
    static String silent(final List<SourceRules.Row> rows, final LocalDateTime now) {
        return "Not available: " + join(rows, row -> out(row, now)) + ".";
    }

    static String stillSilent(final List<SourceRules.Row> rows, final LocalDateTime now) {
        return "Still not available: " + join(rows, row -> out(row, now)) + ".";
    }

    //"Available again: the SOLAX dongle - out from 10:05 to 10:20 (15 min)."; an alert that never got through is named
    static String back(final List<SourceRules.Row> rows) {
        return "Available again: " + join(rows, row -> row.source().words() + " - out from "
            + HouseTimes.hour(row.entity().openedAt()) + " to " + HouseTimes.hour(row.entity().closedAt()) + " ("
            + HouseTimes.length(row.entity().openedAt(), row.entity().closedAt()) + ")" + notTold(row.entity(), "alert")) + ".";
    }

    //"Phase B overloaded since 18:02 (3 min): 23.4 A, above the warning level of 22 A."
    static String overloaded(final Source phase, final SourceAlertEntity episode, final double current,
                             final LocalDateTime now, final double warn) {
        return phase.words() + " overloaded since " + HouseTimes.hour(episode.openedAt()) + " ("
            + HouseTimes.length(episode.openedAt(), now) + "): " + amperes(current) + ", above the warning level of "
            + amperes(warn) + ".";
    }

    //"Phase B below 22 A again: 18.2 A - overloaded from 18:02 to 18:20 (18 min)."
    static String overloadOver(final Source phase, final SourceAlertEntity episode, final double current,
                               final double warn) {
        return phase.words() + " below " + amperes(warn) + " again: " + amperes(current) + " - overloaded from "
            + HouseTimes.hour(episode.openedAt()) + " to " + HouseTimes.hour(episode.closedAt()) + " ("
            + HouseTimes.length(episode.openedAt(), episode.closedAt()) + ")" + notTold(episode, "warning") + ".";
    }

    private static String out(final SourceRules.Row row, final LocalDateTime now) {
        final String state = row.source() == Source.SOLAX ? " does not answer" : " is silent";
        return row.source().words() + state + " since " + HouseTimes.hour(row.entity().openedAt()) + " ("
            + HouseTimes.length(row.entity().openedAt(), now) + ")";
    }

    private static String notTold(final SourceAlertEntity episode, final String what) {
        return episode.lastToldAt() == null ? ", the " + what + " did not get through" : "";
    }

    //"22 A", "23.4 A"
    private static String amperes(final double value) {
        final BigDecimal rounded = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP)
            .stripTrailingZeros();
        return rounded.toPlainString() + " A";
    }

    private static String join(final List<SourceRules.Row> rows, final Function<SourceRules.Row, String> line) {
        return rows.stream().map(line).collect(Collectors.joining("; "));
    }
}

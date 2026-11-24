package cloud.cholewa.energy.frequency;

import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.time.HouseTimes;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

//What the grid frequency is to the household (HAS-503), without I/O - pinned by FrequencyRulesTest. The grid meter decides:
//its frequency furthest from 50 Hz while its reading is fresh; a frequency not known never decides - the counts start
//anew and nothing is told. The SOLAX is the second witness in the words only: it never opens nor closes an episode.
//- No open episode: off (outside low - high) in every pass for offAfter opens one - an alert naming the frequency, the
//  voltages, the likely cause (the planned outages of Tauron now) and what the inverters cannot do; opened at the first
//  off pass. A pass inside the band starts the count anew.
//- An open episode: inside the band in every pass for backAfter closes it at the first of them, with an info and the
//  range seen; off - a reminder every reminderEvery from the last notice. The range is held in memory: after a restart
//  it covers the passes since
final class FrequencyRules {

    static final String SOURCE = "meter";
    static final String KIND = "frequency";
    private static final double NOMINAL = 50.0;

    private FrequencyRules() {
    }

    //what a pass sees: the frequencies of the three phases of the grid meter, Hz, and their voltages, V - null each when
    //not known (all null while the reading is stale); the frequency of the SOLAX, Hz, null while not known or stale
    record Reading(Double a, Double b, Double c, Double voltageA, Double voltageB, Double voltageC, Double solax) {

        //the frequency of the grid meter furthest from 50 Hz, null when no phase is known
        Double hz() {
            return Stream.of(a, b, c)
                .filter(Objects::nonNull)
                .max((left, right) -> Double.compare(Math.abs(left - NOMINAL), Math.abs(right - NOMINAL)))
                .orElse(null);
        }
    }

    //what is held between the passes: the first off pass of an episode not opened yet, the first pass back inside the
    //band of an open one, and the lowest and the highest off frequency seen
    record Memory(LocalDateTime offSince, LocalDateTime backSince, Double lowest, Double highest) {

        static final Memory EMPTY = new Memory(null, null, null, null);

        Memory seen(final double hz) {
            return new Memory(offSince, backSince, lowest == null ? hz : Math.min(lowest, hz),
                highest == null ? hz : Math.max(highest, hz));
        }

        Memory withOffSince(final LocalDateTime since) {
            return new Memory(since, null, lowest, highest);
        }

        Memory withBackSince(final LocalDateTime since) {
            return new Memory(null, since, lowest, highest);
        }
    }

    enum Notice { ALERT, REMINDER, INFO }

    //memory - what to hold for the next pass; row - what to store once the notice was published; notice and text - what
    //to publish, empty when nothing is told
    record Outcome(Memory memory, Optional<SourceAlertEntity> row, Optional<Notice> notice, String text) {

        static Outcome quiet(final Memory memory) {
            return new Outcome(memory, Optional.empty(), Optional.empty(), null);
        }
    }

    //cause - the likely cause in a few words (FrequencyWatch.cause: a generator of Tauron for its outages now, or a
    //generator or a fault of the grid with none announced), read only while off
    static Outcome decide(final Reading reading, final Optional<SourceAlertEntity> open, final Memory memory,
                          final String cause, final FrequencyProperties properties, final LocalDateTime now) {
        final Double hz = reading.hz();
        if (hz == null) {
            return Outcome.quiet(open.isPresent() ? memory.withOffSince(null) : Memory.EMPTY);
        }
        final boolean off = properties.off(hz);
        if (open.isPresent()) {
            return withOpen(reading, open.get(), memory, cause, properties, now);
        }
        if (!off) {
            return Outcome.quiet(Memory.EMPTY);
        }
        final Memory held = memory.seen(hz);
        final LocalDateTime since = held.offSince() == null ? now : held.offSince();
        if (HouseTimes.between(since, now).compareTo(properties.offAfter()) < 0) {
            return Outcome.quiet(held.withOffSince(since));
        }
        return new Outcome(held.withOffSince(since),
            Optional.of(new SourceAlertEntity(null, SOURCE, KIND, since, now, null, null)), Optional.of(Notice.ALERT),
            "The grid frequency is " + hertz(hz) + " since " + HouseTimes.hour(since) + " (" + band(properties)
                + " allowed; " + voltages(reading) + solax(reading) + ") - " + cause
                + ". The inverters do not connect to such a grid: no production of the panels, no charge of "
                + "the battery from the grid.");
    }

    //a reading whose frequency is known - decide checked it
    private static Outcome withOpen(final Reading reading, final SourceAlertEntity open, final Memory memory,
                                    final String cause, final FrequencyProperties properties, final LocalDateTime now) {
        final double hz = reading.hz();
        if (properties.off(hz)) {
            final Memory held = memory.seen(hz).withBackSince(null);
            final boolean due = open.lastToldAt() == null
                || !now.isBefore(open.lastToldAt().plus(properties.reminderEvery()));
            if (!due) {
                return Outcome.quiet(held);
            }
            return new Outcome(held, Optional.of(new SourceAlertEntity(open.id(), open.source(), open.kind(),
                open.openedAt(), now, null, null)), Optional.of(Notice.REMINDER),
                "The grid frequency is still off: " + hertz(hz) + " since " + HouseTimes.hour(open.openedAt()) + " ("
                    + range(held) + "; " + voltages(reading) + ") - " + cause + ".");
        }
        final LocalDateTime back = memory.backSince() == null ? now : memory.backSince();
        if (HouseTimes.between(back, now).compareTo(properties.backAfter()) < 0) {
            return Outcome.quiet(memory.withBackSince(back));
        }
        return new Outcome(Memory.EMPTY, Optional.of(new SourceAlertEntity(open.id(), open.source(), open.kind(),
            open.openedAt(), open.lastToldAt(), back, null)), Optional.of(Notice.INFO),
            "The grid frequency is back: " + hertz(hz) + " - off from " + HouseTimes.hour(open.openedAt()) + " to "
                + HouseTimes.hour(back) + " (" + HouseTimes.length(open.openedAt(), back) + "), " + range(memory) + ".");
    }

    private static String hertz(final double hz) {
        return String.format(Locale.ROOT, "%.2f Hz", hz);
    }

    private static String band(final FrequencyProperties properties) {
        return String.format(Locale.ROOT, "%.2f - %.2f Hz", properties.low(), properties.high());
    }

    private static String range(final Memory memory) {
        return memory.lowest() == null
            ? "the range not known"
            : String.format(Locale.ROOT, "%.2f - %.2f Hz seen", memory.lowest(), memory.highest());
    }

    private static String voltages(final Reading reading) {
        return "the voltages " + volts(reading.voltageA()) + " / " + volts(reading.voltageB()) + " / "
            + volts(reading.voltageC()) + " V";
    }

    private static String volts(final Double voltage) {
        return voltage == null ? "?" : String.valueOf(Math.round(voltage));
    }

    private static String solax(final Reading reading) {
        return reading.solax() == null ? "" : "; the SOLAX measures " + hertz(reading.solax());
    }
}

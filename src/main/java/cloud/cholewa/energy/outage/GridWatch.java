package cloud.cholewa.energy.outage;

import cloud.cholewa.energy.database.GridOutageEntity;

import java.time.LocalDateTime;
import java.util.Objects;

//What the service holds of the grid, in memory (HAS-334) - the decisions are made here first, the database follows.
//loaded - the open outage was read from the database after the start; nothing is decided before.
//lastReadingAt - the house time of the last reading of the SOLAX taken into account, so that one reading counts once.
//streakSignal / streak / streakSince - the signal of the last readings, how many in a row and since when (the house
//time of the first): two in a row decide.
//open - the outage that lasts (its id null until its row was stored); presentSince - since when the grid is there,
//once two readings said so. unsaved - the newest row of an outage the database did not take yet, tried again with
//every pass. The grid is lost while there is an open outage, present since presentSince, not known before either
public record GridWatch(
    boolean loaded,
    LocalDateTime lastReadingAt,
    GridSignal streakSignal,
    int streak,
    LocalDateTime streakSince,
    GridOutageEntity open,
    LocalDateTime presentSince,
    GridOutageEntity unsaved
) {

    static final GridWatch NOT_LOADED = new GridWatch(false, null, null, 0, null, null, null, null);

    static GridWatch loaded(final GridOutageEntity open) {
        return new GridWatch(true, null, null, 0, null, open, null, null);
    }

    GridWatch withReading(final LocalDateTime at, final GridSignal signal) {
        return signal == streakSignal
            ? new GridWatch(loaded, at, signal, streak + 1, streakSince, open, presentSince, unsaved)
            : new GridWatch(loaded, at, signal, 1, at, open, presentSince, unsaved);
    }

    GridWatch withOpen(final GridOutageEntity outage) {
        return new GridWatch(loaded, lastReadingAt, streakSignal, streak, streakSince, outage, presentSince, unsaved);
    }

    GridWatch closed(final LocalDateTime since) {
        return new GridWatch(loaded, lastReadingAt, streakSignal, streak, streakSince, null, since, unsaved);
    }

    GridWatch presentSince(final LocalDateTime since) {
        return new GridWatch(loaded, lastReadingAt, streakSignal, streak, streakSince, open, since, unsaved);
    }

    GridWatch withUnsaved(final GridOutageEntity row) {
        return new GridWatch(loaded, lastReadingAt, streakSignal, streak, streakSince, open, presentSince, row);
    }

    //an outage just closed by the rules is no longer held: the grid is present since its end
    GridWatch settled() {
        return open != null && open.endedAt() != null ? closed(open.endedAt()) : this;
    }

    //the row the database took: no longer unsaved, and the open outage gets its id
    GridWatch stored(final GridOutageEntity row, final GridOutageEntity saved) {
        final GridOutageEntity stillUnsaved = Objects.equals(unsaved, row) ? null : unsaved;
        final GridOutageEntity withId = open != null && open.id() == null && saved.endedAt() == null
            && open.startedAt().equals(saved.startedAt()) ? open.withId(saved.id()) : open;
        return new GridWatch(loaded, lastReadingAt, streakSignal, streak, streakSince, withId, presentSince,
            stillUnsaved);
    }

    boolean confirmed(final GridSignal signal) {
        return streakSignal == signal && streak >= OutageRules.CONFIRMING_READS;
    }
}

package cloud.cholewa.energy.monitor;

import cloud.cholewa.energy.database.SourceAlertEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

//What the monitor of the sources holds, in memory (HAS-332) - the decisions are made here first, the database follows.
//loaded - the open episodes were read from the database after the start; nothing is decided before.
//open - the episode of each source that lasts (its id null until its row was stored).
//unsaved - the rows the database did not take yet, tried again with every pass; one an episode, the newest.
//aboveSince / belowSince - since when a phase is above / below the warning current, by the passes that saw it; in
//memory only, a restart counts anew
record SourceWatch(
    boolean loaded,
    Map<Source, SourceAlertEntity> open,
    List<SourceAlertEntity> unsaved,
    Map<Source, LocalDateTime> aboveSince,
    Map<Source, LocalDateTime> belowSince
) {

    static final SourceWatch NOT_LOADED = new SourceWatch(false, Map.of(), List.of(), Map.of(), Map.of());

    SourceWatch {
        open = copy(open);
        unsaved = List.copyOf(unsaved);
        aboveSince = copy(aboveSince);
        belowSince = copy(belowSince);
    }

    static SourceWatch loaded(final Map<Source, SourceAlertEntity> open) {
        return new SourceWatch(true, open, List.of(), Map.of(), Map.of());
    }

    //a row changed by a decision: held open until it is closed, and stored with the next pass
    SourceWatch with(final SourceAlertEntity row, final Source source) {
        final Map<Source, SourceAlertEntity> nextOpen = new EnumMap<>(Source.class);
        nextOpen.putAll(open);
        if (row.closedAt() == null) {
            nextOpen.put(source, row);
        } else {
            nextOpen.remove(source);
        }
        final List<SourceAlertEntity> nextUnsaved = new ArrayList<>(unsaved.stream()
            .filter(older -> !sameEpisode(older, row))
            .toList());
        nextUnsaved.add(row);
        return new SourceWatch(loaded, nextOpen, nextUnsaved, aboveSince, belowSince);
    }

    SourceWatch withAbove(final Source source, final LocalDateTime since) {
        return new SourceWatch(loaded, open, unsaved, put(aboveSince, source, since), belowSince);
    }

    SourceWatch withoutAbove(final Source source) {
        return new SourceWatch(loaded, open, unsaved, remove(aboveSince, source), belowSince);
    }

    SourceWatch withBelow(final Source source, final LocalDateTime since) {
        return new SourceWatch(loaded, open, unsaved, aboveSince, put(belowSince, source, since));
    }

    SourceWatch withoutBelow(final Source source) {
        return new SourceWatch(loaded, open, unsaved, aboveSince, remove(belowSince, source));
    }

    //the row the database took: no longer unsaved unless a newer one of its episode came meanwhile, and the open
    //episode gets its id
    SourceWatch stored(final SourceAlertEntity row, final SourceAlertEntity saved) {
        final List<SourceAlertEntity> nextUnsaved = unsaved.stream()
            .filter(waiting -> !waiting.equals(row))
            .map(waiting -> waiting.id() == null && sameEpisode(waiting, saved) ? withId(waiting, saved.id()) : waiting)
            .toList();
        final Map<Source, SourceAlertEntity> nextOpen = new EnumMap<>(Source.class);
        open.forEach((source, held) -> nextOpen.put(source,
            held.id() == null && sameEpisode(held, saved) ? withId(held, saved.id()) : held));
        return new SourceWatch(loaded, nextOpen, nextUnsaved, aboveSince, belowSince);
    }

    private static boolean sameEpisode(final SourceAlertEntity one, final SourceAlertEntity other) {
        return one.source().equals(other.source()) && one.kind().equals(other.kind())
            && one.openedAt().equals(other.openedAt());
    }

    private static SourceAlertEntity withId(final SourceAlertEntity row, final Long id) {
        return new SourceAlertEntity(id, row.source(), row.kind(), row.openedAt(), row.lastToldAt(), row.closedAt(), null);
    }

    private static <V> Map<Source, V> put(final Map<Source, V> map, final Source source, final V value) {
        final Map<Source, V> next = new EnumMap<>(Source.class);
        next.putAll(map);
        next.put(source, Objects.requireNonNull(value));
        return next;
    }

    private static <V> Map<Source, V> remove(final Map<Source, V> map, final Source source) {
        final Map<Source, V> next = new EnumMap<>(Source.class);
        next.putAll(map);
        next.remove(source);
        return next;
    }

    private static <V> Map<Source, V> copy(final Map<Source, V> map) {
        final Map<Source, V> copy = new EnumMap<>(Source.class);
        map.forEach((source, value) -> copy.put(source, Objects.requireNonNull(value)));
        return Map.copyOf(copy);
    }
}

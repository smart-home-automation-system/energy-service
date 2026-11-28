package cloud.cholewa.energy.shedding;

import cloud.cholewa.energy.database.LoadSheddingEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

//What the service holds of the load shedding, in memory (HAS-523) - decided here first, the database follows, as the
//watch of the grid does.
//loaded - the open episode was read from the database after the start; nothing is decided before.
//open - the episode that lasts (its id null until its row was stored); gridBackSince - since when the grid is back
//while it lasts, null while the grid is lost. unsaved - the rows the database did not take yet, the oldest first, one an
//episode: the newest row of an episode replaces an older one of it (it holds everything), the row of another episode -
//the end of the last one and the start of the next while the database is down - is kept behind it, so an end is never
//lost to the start that follows it
public record SheddingState(
    boolean loaded,
    LoadSheddingEntity open,
    LocalDateTime gridBackSince,
    List<LoadSheddingEntity> unsaved
) {

    static final SheddingState NOT_LOADED = new SheddingState(false, null, null, List.of());

    public SheddingState {
        unsaved = List.copyOf(unsaved);
    }

    static SheddingState loaded(final LoadSheddingEntity open) {
        return new SheddingState(true, open, null, List.of());
    }

    SheddingState started(final LoadSheddingEntity episode) {
        return new SheddingState(loaded, episode, null, unsaved);
    }

    SheddingState ended() {
        return new SheddingState(loaded, null, null, unsaved);
    }

    SheddingState gridBackSince(final LocalDateTime since) {
        return new SheddingState(loaded, open, since, unsaved);
    }

    //a row to store: in the place of an unsaved row of its episode, or behind the others
    SheddingState withUnsaved(final LoadSheddingEntity row) {
        if (row == null) {
            return this;
        }
        final List<LoadSheddingEntity> rows = new ArrayList<>(unsaved);
        final int same = indexOfEpisode(rows, row);
        if (same >= 0) {
            rows.set(same, row);
        } else {
            rows.add(row);
        }
        return new SheddingState(loaded, open, gridBackSince, rows);
    }

    //the row the database took: no longer unsaved - unless a newer row of its episode took its place meanwhile - and
    //the open episode gets its id, as does a newer row of the same episode still waiting
    SheddingState stored(final LoadSheddingEntity row, final LoadSheddingEntity saved) {
        final List<LoadSheddingEntity> rows = unsaved.stream()
            .filter(waiting -> !Objects.equals(waiting, row))
            .map(waiting -> waiting.id() == null && waiting.startedAt().equals(saved.startedAt())
                ? waiting.withId(saved.id()) : waiting)
            .toList();
        final LoadSheddingEntity withId = open != null && open.id() == null
            && open.startedAt().equals(saved.startedAt()) ? open.withId(saved.id()) : open;
        return new SheddingState(loaded, withId, gridBackSince, rows);
    }

    private static int indexOfEpisode(final List<LoadSheddingEntity> rows, final LoadSheddingEntity row) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).startedAt().equals(row.startedAt())) {
                return i;
            }
        }
        return -1;
    }
}

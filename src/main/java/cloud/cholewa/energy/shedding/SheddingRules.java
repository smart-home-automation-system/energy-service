package cloud.cholewa.energy.shedding;

import cloud.cholewa.energy.api.model.GridStateReply;
import cloud.cholewa.energy.database.LoadSheddingEntity;

import java.time.Duration;
import java.time.LocalDateTime;

//The rules of the load shedding (HAS-523, epic HAS-520), without I/O. The grid is the SOLAX's, as GET /status answers it
//(the watch of the grid - two readings in a row decide, a silent SOLAX is not known).
//Starts at once with the grid lost while the switch is on (owner, 2026-11-28 - not below a threshold of the battery);
//a grid not known never starts it. Ends once the grid is back without a break for the stable time - counted from the
//return, anew after every new loss (the frequency is not judged: a generator of Tauron is the grid) - or at once when the
//switch is turned off. A grid not known while it lasts holds it: neither counted as back nor as lost
public final class SheddingRules {

    private SheddingRules() {
    }

    enum Change { NONE, STARTED, ENDED }

    //next - what the service holds after the pass; row - the row to store, null when nothing changed
    record Decision(SheddingState next, LoadSheddingEntity row, Change change) {

        static Decision none(final SheddingState next) {
            return new Decision(next, null, Change.NONE);
        }
    }

    static Decision decide(final SheddingState held, final boolean enabled, final GridStateReply grid,
                           final LocalDateTime now, final Duration stableAfter) {
        final boolean lost = GridStateReply.LOST.equals(grid.state());
        final boolean present = GridStateReply.PRESENT.equals(grid.state()) && grid.since() != null;
        if (held.open() == null) {
            if (enabled && lost) {
                final LoadSheddingEntity episode = LoadSheddingEntity.started(now);
                return new Decision(held.started(episode), episode, Change.STARTED);
            }
            return Decision.none(held);
        }
        if (!enabled) {
            return new Decision(held.ended(), held.open().ended(now, LoadSheddingEntity.BY_SWITCH), Change.ENDED);
        }
        if (lost) {
            return Decision.none(held.gridBackSince(null));
        }
        if (present) {
            return now.isBefore(grid.since().plus(stableAfter))
                ? Decision.none(held.gridBackSince(grid.since()))
                : new Decision(held.ended(), held.open().ended(now, LoadSheddingEntity.BY_GRID), Change.ENDED);
        }
        return Decision.none(held);
    }

    //when the load shedding ends while the grid is back and its stable time runs; null otherwise
    static LocalDateTime endsAt(final SheddingState state, final Duration stableAfter) {
        return state.open() != null && state.gridBackSince() != null ? state.gridBackSince().plus(stableAfter) : null;
    }
}

package cloud.cholewa.energy.outage;

import cloud.cholewa.energy.api.model.GridStateReply;
import cloud.cholewa.energy.api.model.OutageReply;
import cloud.cholewa.energy.database.GridOutageEntity;
import cloud.cholewa.energy.database.GridOutageRepository;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.solax.SolaxTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

//The outages of the grid for the API (HAS-334): the stored ones of a range, and the grid now from what the watch holds
@Service
@RequiredArgsConstructor
public class GridOutageService {

    static final Duration LONGEST_RANGE = Duration.ofDays(366);

    private final GridOutageRepository gridOutageRepository;
    private final GridOutageWatch gridOutageWatch;
    private final SolaxTracker solaxTracker;
    private final SolaxProperties solaxProperties;
    private final Clock clock;

    //the outages that started in [from, to), the oldest first; from before to, at most 366 days
    public Mono<List<OutageReply>> outages(final LocalDateTime from, final LocalDateTime to) {
        return Mono.just(from)
            .filter(start -> start.isBefore(to) && !start.plus(LONGEST_RANGE).isBefore(to))
            .switchIfEmpty(Mono.error(() -> EnergyException.invalidRange(
                "from must be before to, at most " + LONGEST_RANGE.toDays() + " days apart")))
            .flatMapMany(start -> gridOutageRepository
                .findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAt(start, to))
            .map(GridOutageService::replyOf)
            .collectList();
    }

    //an open outage is lost, as stored; present only while the SOLAX that saw it is not stale - a silent SOLAX is
    //not known, never read as present
    public GridStateReply grid() {
        return stateOf(gridOutageWatch.current(),
            solaxTracker.current().isStale(LocalDateTime.now(clock), solaxProperties.staleAfter()));
    }

    static GridStateReply stateOf(final GridWatch watch, final boolean solaxStale) {
        if (watch.open() != null && watch.open().endedAt() == null) {
            return new GridStateReply(GridStateReply.LOST, watch.open().startedAt());
        }
        if (watch.presentSince() != null && !solaxStale) {
            return new GridStateReply(GridStateReply.PRESENT, watch.presentSince());
        }
        return GridStateReply.notKnown();
    }

    private static OutageReply replyOf(final GridOutageEntity row) {
        return new OutageReply(row.startedAt(), row.endedAt(), row.socAtStart(), row.socAtEnd(), row.lowestSoc());
    }
}

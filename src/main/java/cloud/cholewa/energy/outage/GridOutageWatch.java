package cloud.cholewa.energy.outage;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.database.GridOutageEntity;
import cloud.cholewa.energy.database.GridOutageRepository;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.solax.SolaxTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//Watches the grid through the SOLAX (HAS-334): every 15 s the rules of OutageRules on the state of the SOLAX, the
//decision held in memory at once, the notifications sent, then the row of grid_outage stored. The alert does not wait
//for the database (a managed one, outside the house): a row it does not take is tried again with every pass.
//After a start nothing is decided before the open outage is read from the database (a failed read is tried again
//with the next pass): a restart during an outage keeps its row, and the reads of the SOLAX close it when the grid
//is back - a restart while the database cannot be read decides nothing until it can. A notification neither fails
//nor stalls the pass: each is cut off after 6 s and its failure logged with its text. The pass is bounded below its
//delay
@Slf4j
@Component
@RequiredArgsConstructor
public class GridOutageWatch {

    static final Duration LIMIT = Duration.ofSeconds(14);
    //two notices of one pass (a low battery and the reminder) fit in the bound of the pass
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(6);

    private final SolaxTracker solaxTracker;
    private final GridOutageRepository gridOutageRepository;
    private final NotificationPublisher notificationPublisher;
    private final OutageProperties outageProperties;
    private final SolaxProperties solaxProperties;
    private final Clock clock;
    private final AtomicReference<GridWatch> watch = new AtomicReference<>(GridWatch.NOT_LOADED);

    public GridWatch current() {
        return watch.get();
    }

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "PT15S")
    Mono<Void> watch() {
        return ScheduledPass.bounded("Grid outage watch", LIMIT, () -> load().then(Mono.defer(this::step)));
    }

    private Mono<Void> load() {
        return Mono.fromSupplier(watch::get)
            .filter(held -> !held.loaded())
            .flatMap(held -> gridOutageRepository.findFirstByEndedAtIsNull()
                .doOnNext(open -> log.info("The outage of the grid since {} is still open", open.startedAt()))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .doOnNext(open -> watch.set(GridWatch.loaded(open.orElse(null))))
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The open outage of the grid could not be read, tried again with the next pass: {}",
                    failure.toString()))))
            .then();
    }

    //decided in memory and held at once, the notifications sent, then the row stored - the newest row of this pass,
    //or the one an earlier pass could not store
    private Mono<Void> step() {
        return Mono.fromSupplier(watch::get)
            .filter(GridWatch::loaded)
            .flatMap(held -> {
                final OutageRules.Decision decision = OutageRules.decide(held, solaxTracker.current(),
                    LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS), outageProperties,
                    solaxProperties.batteryCapacity());
                final GridOutageEntity row = Optional.ofNullable(decision.row()).orElse(held.unsaved());
                watch.set(decision.next().settled().withUnsaved(row));
                return notify(decision).then(store(row));
            });
    }

    //a row the database does not take stays unsaved and is tried again with the next pass; the newest row of an
    //outage replaces an older one not stored yet - it holds everything the older did
    private Mono<Void> store(final GridOutageEntity row) {
        return Mono.justOrEmpty(row)
            .flatMap(unsaved -> gridOutageRepository.save(unsaved)
                .doOnNext(saved -> watch.updateAndGet(held -> held.stored(unsaved, saved)))
                .then()
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The outage of the grid since {} could not be stored, tried again with the next pass: {}",
                    unsaved.startedAt(), failure.toString()))));
    }

    private Mono<Void> notify(final OutageRules.Decision decision) {
        return Flux.fromIterable(decision.notices())
            .concatMap(this::send)
            .then();
    }

    private Mono<Void> send(final OutageRules.Notice notice) {
        final Mono<Void> publish = switch (notice.kind()) {
            case ALERT_SMS -> notificationPublisher.publishAlertSms(notice.text());
            case REMINDER -> notificationPublisher.publishReminder(notice.text());
            case INFO_SMS -> notificationPublisher.publishInfoSms(notice.text());
        };
        return publish
            .timeout(NOTICE_TIMEOUT)
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                "The notification of the outage of the grid was not published - {}: {}",
                failure.toString(), notice.text())));
    }
}

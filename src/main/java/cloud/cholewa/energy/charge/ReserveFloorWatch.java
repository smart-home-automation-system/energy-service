package cloud.cholewa.energy.charge;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.database.BatteryChargeRunRepository;
import cloud.cholewa.energy.database.EnergyReserveEntity;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
import cloud.cholewa.energy.solax.SolaxSettingsTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

//The battery reserve the inverter does not hold (HAS-518): every minute the reserve of the service against the
//settings of the SOLAX read from its dongle (SolaxSettingsTracker, in memory - never a call to the dongle), judged by
//ReserveFloorRules. It reads only - the three queries one after the other (pool 2), outside the lane of the commands.
//Kept in source_alert (kind reserve, source solax, V20): the table follows the broker, as the frequency watch - a row
//is written only once its notice was published, so one that failed is due again with the next pass; a told row the
//database did not take is held and stored first by the next pass, which decides nothing until it is (a notice is never
//told twice). The count of the passes and that row are in memory - a restart counts anew. Notices on the technical
//channel, never an SMS. Bounded at LIMIT
@Slf4j
@Component
@RequiredArgsConstructor
public class ReserveFloorWatch {

    static final Duration LIMIT = Duration.ofSeconds(50);
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(10);
    private static final Set<String> WRITTEN = Set.of(ReserveService.SENT, ReserveService.TAKEN);

    private final ReserveService reserveService;
    private final BatteryChargeRunRepository runs;
    private final SolaxSettingsTracker settingsTracker;
    private final SourceAlertRepository sourceAlertRepository;
    private final NotificationPublisher notificationPublisher;
    private final ReserveFloorProperties properties;
    private final Clock clock;
    private final AtomicReference<LocalDateTime> since = new AtomicReference<>();
    private final AtomicReference<SourceAlertEntity> unsaved = new AtomicReference<>();

    @Scheduled(initialDelayString = "PT55S", fixedDelayString = "PT1M")
    Mono<Void> check() {
        return ScheduledPass.bounded("battery reserve floor watch", LIMIT, () -> Mono.defer(this::checkOnce));
    }

    Mono<Void> checkOnce() {
        final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        return storeUnsaved()
            .then(Mono.defer(() -> seen(now)))
            .flatMap(seen -> sourceAlertRepository
                .findByClosedAtIsNullAndSourceAndKind(ReserveFloorRules.SOURCE, ReserveFloorRules.KIND)
                .next()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .map(open -> ReserveFloorRules.decide(seen, open, since.get(), properties, now)))
            .flatMap(this::act)
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The floor of the battery reserve could not be judged, tried again with the next pass: {}",
                failure.toString())));
    }

    private Mono<Void> storeUnsaved() {
        return Mono.justOrEmpty(unsaved.get())
            .flatMap(row -> sourceAlertRepository.save(row)
                .doOnNext(saved -> unsaved.compareAndSet(row, null)))
            .then();
    }

    private Mono<ReserveFloorRules.Seen> seen(final LocalDateTime now) {
        return reserveService.read()
            .flatMap(row -> busy()
                .map(busy -> ReserveFloorRules.see(ReserveRules.inForce(row.enabled(), row.until(), now), row.level(),
                    settled(row) && !busy, settingsTracker.current().map(SolaxSettingsTracker.Read::settings))));
    }

    //a charge open or being ended: the inverter runs its Self Use for now, the end writes the reserve back
    private Mono<Boolean> busy() {
        return runs.findFirstByOrderByRequestedAtDesc()
            .map(ReserveService::busy)
            .defaultIfEmpty(false);
    }

    private static boolean settled(final EnergyReserveEntity row) {
        return WRITTEN.contains(row.writeOutcome());
    }

    //the count moves on with a quiet pass or a notice published; a notice not published keeps the count it was decided
    //on, so the next pass decides it again
    private Mono<Void> act(final ReserveFloorRules.Outcome outcome) {
        return Mono.justOrEmpty(outcome.notice())
            .switchIfEmpty(Mono.fromRunnable(() -> since.set(outcome.since())))
            .flatMap(notice -> publish(notice, outcome.text()))
            .doOnNext(sent -> since.set(outcome.since()))
            .flatMap(sent -> Mono.justOrEmpty(outcome.row()))
            .flatMap(row -> sourceAlertRepository.save(row)
                .onErrorResume(failure -> Mono.fromRunnable(() -> {
                    unsaved.set(row);
                    log.warn("The told episode of the battery reserve floor was not stored, stored first by the next "
                        + "pass: {}", failure.toString());
                })))
            .then();
    }

    private Mono<Boolean> publish(final ReserveFloorRules.Notice notice, final String text) {
        final Mono<Void> sent = switch (notice) {
            case WARNING, REMINDER -> notificationPublisher.publishReminder(text);
            case INFO -> notificationPublisher.publishInfo(text);
        };
        return sent.timeout(NOTICE_TIMEOUT)
            .thenReturn(true)
            .doOnNext(published -> log.info("Battery reserve floor notification published: {}", text))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                "The battery reserve floor notification was not published, tried again with the next pass - {}: {}",
                failure.toString(), text)));
    }
}

package cloud.cholewa.energy.charge;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.api.model.BatteryDirection;
import cloud.cholewa.energy.api.model.BatteryReply;
import cloud.cholewa.energy.database.BatteryChargeRunRepository;
import cloud.cholewa.energy.database.EnergyReserveEntity;
import cloud.cholewa.energy.database.GridOutageRepository;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
import cloud.cholewa.energy.inverter.BatteryService;
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

//The battery below its reserve (HAS-495): every minute the state of charge of the SOLAX battery (BatteryService - the
//dongle, or the SolaX cloud standing for it; never stale) against the reserve of the service, judged by ReserveSocRules
//while the grid is present (no open grid_outage), no charge is open or being ended, the last write of the reserve was
//sent or taken and no episode of the floor of the inverter (ReserveFloorWatch) is open - that one tells the same cause
//first. It reads only - its queries one after the other, outside the lane of the commands.
//Kept in source_alert (kind reserve, source battery, V21) as the watch of the floor (ReserveFloorWatch, HAS-518) keeps
//its own: the table follows the broker - a row is written only once its notice was published; a told row the database
//did not take is held and stored first by the next pass, which decides nothing until it is (a notice is never told
//twice). The count of the passes and that row are in memory - a restart counts anew. Notices on the technical channel,
//never an SMS. Bounded at LIMIT
@Slf4j
@Component
@RequiredArgsConstructor
public class ReserveSocWatch {

    static final Duration LIMIT = Duration.ofSeconds(50);
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(10);
    private static final Set<String> WRITTEN = Set.of(ReserveService.SENT, ReserveService.TAKEN);

    private final ReserveService reserveService;
    private final BatteryChargeRunRepository runs;
    private final GridOutageRepository outages;
    private final BatteryService batteryService;
    private final SourceAlertRepository sourceAlertRepository;
    private final NotificationPublisher notificationPublisher;
    private final ReserveSocProperties properties;
    private final Clock clock;
    private final AtomicReference<LocalDateTime> since = new AtomicReference<>();
    private final AtomicReference<SourceAlertEntity> unsaved = new AtomicReference<>();

    @Scheduled(initialDelayString = "PT65S", fixedDelayString = "PT1M")
    Mono<Void> check() {
        return ScheduledPass.bounded("battery reserve soc watch", LIMIT, () -> Mono.defer(this::checkOnce));
    }

    Mono<Void> checkOnce() {
        final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        return storeUnsaved()
            .then(Mono.defer(() -> seen(now)))
            .flatMap(seen -> sourceAlertRepository
                .findByClosedAtIsNullAndSourceAndKind(ReserveSocRules.SOURCE, ReserveSocRules.KIND)
                .next()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .map(open -> ReserveSocRules.decide(seen, open, since.get(), properties, now)))
            .flatMap(this::act)
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The battery against its reserve could not be judged, tried again with the next pass: {}",
                failure.toString())));
    }

    private Mono<Void> storeUnsaved() {
        return Mono.justOrEmpty(unsaved.get())
            .flatMap(row -> sourceAlertRepository.save(row)
                .doOnNext(saved -> unsaved.compareAndSet(row, null)))
            .then();
    }

    private Mono<ReserveSocRules.Seen> seen(final LocalDateTime now) {
        return reserveService.read()
            .flatMap(row -> busy()
                .flatMap(busy -> gridOut()
                    .flatMap(out -> floorTold()
                        .flatMap(floor -> battery()
                            .map(battery -> ReserveSocRules.see(ReserveRules.inForce(row.enabled(), row.until(), now),
                                row.level(), settled(row) && !busy && !out && !floor, battery.map(BatteryReply::soc),
                                battery.map(ReserveSocWatch::charging).orElse(false), properties.tolerance()))))));
    }

    //the floor of the inverter below the reserve already told (ReserveFloorWatch, HAS-518): the battery below it is the
    //same cause - one warning, not two (the /code-review of HAS-495)
    private Mono<Boolean> floorTold() {
        return sourceAlertRepository.findByClosedAtIsNullAndSourceAndKind(ReserveFloorRules.SOURCE,
                ReserveFloorRules.KIND)
            .hasElements();
    }

    //the battery takes power now - by its power and the dead band of the dashboard, a power not known is not charging
    private static boolean charging(final BatteryReply battery) {
        return battery.power() != null && BatteryDirection.of(battery.power()) == BatteryDirection.CHARGING;
    }

    //a charge open or being ended: the inverter runs its Self Use for now, the end writes the reserve back
    private Mono<Boolean> busy() {
        return runs.findFirstByOrderByRequestedAtDesc()
            .map(ReserveService::busy)
            .defaultIfEmpty(false);
    }

    //an outage of the grid going on: the house runs on the battery, the drop is the purpose of the reserve
    private Mono<Boolean> gridOut() {
        return outages.findFirstByEndedAtIsNull()
            .map(outage -> true)
            .defaultIfEmpty(false);
    }

    //the battery while fresh, with a state of charge
    private Mono<Optional<BatteryReply>> battery() {
        return batteryService.battery()
            .filter(battery -> !battery.stale() && battery.soc() != null)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty());
    }

    private static boolean settled(final EnergyReserveEntity row) {
        return WRITTEN.contains(row.writeOutcome());
    }

    //the count moves on with a quiet pass or a notice published; a notice not published keeps the count it was decided
    //on, so the next pass decides it again
    private Mono<Void> act(final ReserveSocRules.Outcome outcome) {
        return Mono.justOrEmpty(outcome.notice())
            .switchIfEmpty(Mono.fromRunnable(() -> since.set(outcome.since())))
            .flatMap(notice -> publish(notice, outcome.text()))
            .doOnNext(sent -> since.set(outcome.since()))
            .flatMap(sent -> Mono.justOrEmpty(outcome.row()))
            .flatMap(row -> sourceAlertRepository.save(row)
                .onErrorResume(failure -> Mono.fromRunnable(() -> {
                    unsaved.set(row);
                    log.warn("The told episode of the battery below its reserve was not stored, stored first by the "
                        + "next pass: {}", failure.toString());
                })))
            .then();
    }

    private Mono<Boolean> publish(final ReserveSocRules.Notice notice, final String text) {
        final Mono<Void> sent = switch (notice) {
            case WARNING, REMINDER -> notificationPublisher.publishReminder(text);
            case INFO -> notificationPublisher.publishInfo(text);
        };
        return sent.timeout(NOTICE_TIMEOUT)
            .thenReturn(true)
            .doOnNext(published -> log.info("Battery reserve soc notification published: {}", text))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                "The battery reserve soc notification was not published, tried again with the next pass - {}: {}",
                failure.toString(), text)));
    }
}

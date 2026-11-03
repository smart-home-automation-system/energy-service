package cloud.cholewa.energy.charge;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.energy.api.model.BatteryReply;
import cloud.cholewa.energy.api.model.ChargeReply;
import cloud.cholewa.energy.api.model.ChargeRequest;
import cloud.cholewa.energy.api.model.ChargeRunReply;
import cloud.cholewa.energy.database.BatteryChargeRunEntity;
import cloud.cholewa.energy.database.BatteryChargeRunRepository;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.inverter.BatteryService;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.solaxcloud.SolaxCloudException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

//The charge of the SOLAX battery from the grid on demand (HAS-336). Every change runs in the lane of InverterCommands, so
//a request and the pass of the watch never change a run at once.
//A start: the target and the source checked, no other run open or being ended (CHARGE_IN_PROGRESS), a write allowed
//(WRITE_TOO_SOON), the SOLAX of the registry, its SOC known and fresh (INVERTER_UNAVAILABLE) and below the target
//(TARGET_REACHED); the run stored, then the command sent - its request id stored, the delivery left to ChargeWatch. A
//command the cloud refuses ends the run as failed (DEVICE_FAILED) - the inverter took nothing.
//A cancel ends the open run and sets the baseline of Self Use back; a baseline the cloud refuses is tried again by the
//watch. The end of a run is told once its baseline was set back
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargeService {

    static final Duration LONGEST_RANGE = Duration.ofDays(366);
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(6);
    static final int DEFAULT_TARGET = 100;

    private final BatteryChargeRunRepository repository;
    private final InverterCommands commands;
    private final BatteryService batteryService;
    private final NotificationPublisher notificationPublisher;
    private final ChargeProperties properties;
    private final SolaxProperties solaxProperties;
    private final ChargeWatchFlag watchFlag;
    private final Clock clock;

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    public Mono<ChargeRunReply> start(final ChargeRequest request, final String source) {
        final ChargeRequest asked = Optional.ofNullable(request).orElse(new ChargeRequest(null, null));
        final int target = Optional.ofNullable(asked.targetSoc()).orElse(DEFAULT_TARGET);
        return commands.serially(Mono.fromRunnable(() -> ChargeRules.check(target, source))
            .then(repository.findFirstByOrderByRequestedAtDesc())
            .filter(last -> last.open() || last.restorePending())
            .flatMap(busy -> Mono.<BatteryChargeRunEntity>error(EnergyException.chargeInProgress()))
            .then(Mono.fromSupplier(commands::writable))
            .filter(allowed -> allowed)
            .switchIfEmpty(Mono.error(EnergyException::writeTooSoon))
            .then(Mono.fromSupplier(commands::serial))
            .flatMap(serial -> soc().flatMap(soc -> begin(serial, soc, target, asked.until(), source))))
            .map(ChargeService::reply);
    }

    private Mono<BatteryChargeRunEntity> begin(final String serial, final double soc, final int target,
                                               final LocalDateTime until, final String source) {
        final LocalDateTime now = now();
        return Mono.just(soc)
            .filter(value -> value < target)
            .switchIfEmpty(Mono.error(() -> EnergyException.targetReached(soc, target)))
            .map(below -> ChargeRules.window(now, soc, target, until, solaxProperties.batteryCapacity(), properties))
            .flatMap(window -> repository.save(new BatteryChargeRunEntity(null, now, source, target, window.start(),
                    window.end(), null, null, null, soc, null, null, null, null, null, null))
                .flatMap(run -> commands.charge(serial, target, window.start(), window.end())
                    .flatMap(requestId -> repository.save(run.withRequestId(requestId)))
                    .doOnNext(sent -> {
                        watchFlag.raise();
                        log.info("Battery charge {} requested ({}): to {} %, {} - {}, command {}", sent.id(), source,
                            target, window.start(), window.end(), sent.requestId());
                    })
                    .onErrorResume(SolaxCloudException.class, refused -> refused(run, refused))));
    }

    //a command the cloud refused: the run failed, nothing to set back
    private Mono<BatteryChargeRunEntity> refused(final BatteryChargeRunEntity run, final SolaxCloudException refused) {
        final LocalDateTime now = now();
        log.warn("The SolaX cloud refused the charge {}: {}", run.id(), refused.getMessage());
        return repository.save(run.withEndedAt(now).withEndedBy(ChargeRules.BY_FAILURE).withFailure("DEVICE_FAILED")
                .withRestoredAt(now))
            .then(Mono.error(EnergyException.deviceFailed("The SolaX cloud refused the command")));
    }

    public Mono<ChargeRunReply> cancel() {
        return commands.serially(repository.findFirstByOrderByRequestedAtDesc()
                .filter(BatteryChargeRunEntity::open)
                .switchIfEmpty(Mono.error(EnergyException::chargeNotFound))
                .flatMap(run -> Mono.fromSupplier(commands::writable)
                    .filter(allowed -> allowed)
                    .switchIfEmpty(Mono.error(EnergyException::writeTooSoon))
                    .then(socOrNull())
                    .flatMap(soc -> repository.save(run.withEndedAt(now()).withEndedBy(ChargeRules.BY_CANCEL)
                        .withSocAtEnd(soc.orElse(null))))
                    .flatMap(this::restore)))
            .map(ChargeService::reply);
    }

    //the baseline of Self Use set back after a run, the end told; a baseline the cloud refused stays due and is tried
    //again by the watch
    Mono<BatteryChargeRunEntity> restore(final BatteryChargeRunEntity run) {
        return Mono.fromCallable(commands::serial)
            .flatMap(serial -> commands.restore(serial))
            .flatMap(requestId -> repository.save(run.withRestoreRequestId(requestId).withRestoredAt(now())))
            .flatMap(restored -> tell(ChargeMessages.ended(restored)).thenReturn(restored))
            .doOnNext(restored -> log.info("Battery charge {} ended ({}), the baseline of Self Use set back, command {}",
                restored.id(), restored.endedBy(), restored.restoreRequestId()))
            .onErrorResume(failure -> Mono.fromCallable(() -> {
                log.warn("The baseline of Self Use after the charge {} was not set back, tried again by the watch: {}",
                    run.id(), failure.getMessage());
                return run;
            }));
    }

    public Mono<ChargeReply> current() {
        return repository.findFirstByOrderByRequestedAtDesc()
            .filter(last -> last.open() || last.restorePending())
            .flatMap(run -> batteryService.battery()
                .map(battery -> new ChargeReply(true, reply(run), battery.soc(), battery.power(),
                    ChargeRules.minutesLeft(battery.soc(), run.targetSoc(), solaxProperties.batteryCapacity(),
                        properties).orElse(null))))
            .defaultIfEmpty(new ChargeReply(false, null, null, null, null));
    }

    //the runs requested in [from, to), the oldest first; from before to, at most 366 days
    public Mono<List<ChargeRunReply>> runs(final LocalDateTime from, final LocalDateTime to) {
        return Mono.just(from)
            .filter(start -> start.isBefore(to) && !start.plus(LONGEST_RANGE).isBefore(to))
            .switchIfEmpty(Mono.error(() -> EnergyException.invalidRange(
                "from must be before to, at most " + LONGEST_RANGE.toDays() + " days apart")))
            .flatMapMany(start -> repository.findByRequestedAtGreaterThanEqualAndRequestedAtLessThanOrderByRequestedAt(
                start, to))
            .map(ChargeService::reply)
            .collectList();
    }

    //the SOC of the battery now - fresh from the dongle, or the SolaX cloud standing for it (BatteryService)
    private Mono<Double> soc() {
        return batteryService.battery()
            .filter(battery -> !battery.stale())
            .mapNotNull(BatteryReply::soc)
            .switchIfEmpty(Mono.error(() -> EnergyException.inverterUnavailable(
                "The state of charge of the battery is not known")));
    }

    Mono<Optional<Double>> socOrNull() {
        return batteryService.battery()
            .map(battery -> Optional.ofNullable(battery.soc()).filter(soc -> !battery.stale()))
            .defaultIfEmpty(Optional.empty());
    }

    Mono<Void> tell(final String text) {
        return notificationPublisher.publishInfo(text)
            .timeout(NOTICE_TIMEOUT)
            .doOnSuccess(sent -> log.info("Battery charge notice published: {}", text))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                "The notice of a battery charge was not published - {}: {}", failure.toString(), text)));
    }

    static ChargeRunReply reply(final BatteryChargeRunEntity run) {
        return new ChargeRunReply(run.id(), stateOf(run), run.source(), run.requestedAt(), run.targetSoc(),
            run.windowStart(), run.windowEnd(), run.deliveryStatus(), run.deliveredAt(), run.socAtStart(),
            run.socAtEnd(), run.endedAt(), run.endedBy(), run.failure(), run.restoredAt());
    }

    static String stateOf(final BatteryChargeRunEntity run) {
        return switch (ChargeRules.stageOf(run)) {
            case REQUESTED -> "requested";
            case CHARGING -> "charging";
            case ENDING -> "ending";
            case OVER -> ChargeRules.BY_FAILURE.equals(run.endedBy()) ? "failed" : "ended";
        };
    }
}

package cloud.cholewa.energy.outage;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.database.GridOutageEntity;
import cloud.cholewa.energy.database.GridOutageRepository;
import cloud.cholewa.energy.database.MeterUptimeEntity;
import cloud.cholewa.energy.database.MeterUptimeRepository;
import cloud.cholewa.energy.meter.MeterProperties;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

//The outages of the grid seen after the fact (HAS-505): every minute the uptime of the grid meter (the sys component of
//Shelly.GetStatus), its start against the time it was last seen (meter_uptime, V19). A start after it with the reset
//reason power-on is an outage the live watch could not see - the house and the cluster down with the grid - stored in
//grid_outage as inferred between the two bounds and told once on the technical channel (an info, never an SMS); never
//over an outage the live watch stored or holds. The row of the meter is written only after the outage was stored, so a
//store that failed is decided again with the next pass. A read that does not answer decides and writes nothing - the
//meter is off; its first failure in a row is a WARN. The pass is bounded below its delay
@Slf4j
@Component
@RequiredArgsConstructor
public class MeterRestartWatch {

    static final Duration LIMIT = Duration.ofSeconds(45);
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(6);

    private final MeterProperties meterProperties;
    private final ShellyRpcClient shellyRpcClient;
    private final MeterUptimeRepository meterUptimeRepository;
    private final GridOutageRepository gridOutageRepository;
    private final GridOutageWatch gridOutageWatch;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;
    private final AtomicReference<MeterUptimeEntity> stored = new AtomicReference<>();
    private final AtomicBoolean failing = new AtomicBoolean();

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT1M")
    Mono<Void> check() {
        return ScheduledPass.bounded("Meter restart watch", LIMIT, () -> Mono.defer(this::pass));
    }

    private Mono<Void> pass() {
        return storedRow()
            .zipWhen(row -> sys())
            .flatMap(rowAndSys -> {
                //the start on the timeline, then in house time: a wall clock less the uptime is an hour off across a
                //change of the clocks
                final Instant instant = clock.instant().truncatedTo(ChronoUnit.SECONDS);
                final LocalDateTime now = LocalDateTime.ofInstant(instant, clock.getZone());
                final LocalDateTime bootedAt = LocalDateTime.ofInstant(
                    instant.minusSeconds(rowAndSys.getT2().uptime()), clock.getZone());
                final MeterRestartRules.Verdict verdict =
                    MeterRestartRules.judge(rowAndSys.getT1(), bootedAt, rowAndSys.getT2().resetReason());
                return act(verdict, now)
                    .then(Mono.defer(() -> remember(rowAndSys.getT1().withSeenAt(now).withBootedAt(bootedAt))));
            })
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The restarts of the grid meter could not be checked, tried again with the next pass: {}",
                failure.toString())));
    }

    //the row read once after a start, then held; a missing row is an error - the migration inserts it
    private Mono<MeterUptimeEntity> storedRow() {
        return Mono.justOrEmpty(stored.get())
            .switchIfEmpty(Mono.defer(() -> meterUptimeRepository.findById(MeterUptimeEntity.ROW_ID)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("meter_uptime has no row")))
                .doOnNext(stored::set)));
    }

    private Mono<MeterSys> sys() {
        return shellyRpcClient.status(meterProperties.address())
            .flatMap(answer -> Mono.justOrEmpty(MeterSys.of(answer))
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("the status has no uptime of sys"))))
            .doOnNext(sys -> answered())
            .onErrorResume(failure -> Mono.fromRunnable(() -> failed(failure)));
    }

    //the first failure of a streak is a WARN, the first answer after it an INFO - the meter is off with the grid
    private void answered() {
        if (failing.getAndSet(false)) {
            log.info("The grid meter answers its status again");
        }
    }

    private void failed(final Throwable failure) {
        if (!failing.getAndSet(true)) {
            log.warn("The status of the grid meter could not be read: {}", failure.toString());
        }
    }

    private Mono<Void> act(final MeterRestartRules.Verdict verdict, final LocalDateTime now) {
        return switch (verdict.kind()) {
            case NONE -> Mono.empty();
            case RESTART -> Mono.fromRunnable(() -> log.info(
                "The grid meter restarted at {} (reset reason {}) - not a loss of the grid", verdict.to(),
                verdict.resetReason()));
            case NOT_CONCLUSIVE -> Mono.fromRunnable(() -> log.warn(
                "The grid meter started at {} without a reset reason - a loss of the grid between {} and {} is not "
                    + "stored", verdict.to(), verdict.from(), verdict.to()));
            case OUTAGE -> outage(verdict.from(), verdict.to(), now);
        };
    }

    //neither held by the live watch nor stored - then stored and told
    private Mono<Void> outage(final LocalDateTime from, final LocalDateTime to, final LocalDateTime now) {
        return Mono.fromSupplier(gridOutageWatch::current)
            .filter(watch -> !MeterRestartRules.heldLive(watch, from, to))
            .switchIfEmpty(Mono.fromRunnable(() -> log.info(
                "The grid meter lost its power between {} and {} - the outage is held by the live watch", from, to)))
            .flatMap(watch -> gridOutageRepository.existsOverlapping(from, to)
                .filter(Boolean.FALSE::equals)
                .switchIfEmpty(Mono.fromRunnable(() -> log.info(
                    "The grid meter lost its power between {} and {} - the outage is stored already", from, to))))
            .flatMap(free -> gridOutageRepository.save(GridOutageEntity.inferred(from, to, now)))
            .doOnNext(row -> log.info("The grid was lost while the service was down, between {} and {}", from, to))
            .flatMap(row -> tell(OutageMessages.afterTheFact(from, to, now)));
    }

    private Mono<Void> tell(final String text) {
        return notificationPublisher.publishInfo(text)
            .timeout(NOTICE_TIMEOUT)
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                "The notification of the outage seen after the fact was not published - {}: {}", failure.toString(),
                text)));
    }

    //a failed write keeps the row held before: the next pass judges against it again, and an outage it stored is
    //found by the overlap
    private Mono<Void> remember(final MeterUptimeEntity row) {
        return meterUptimeRepository.save(row)
            .doOnNext(stored::set)
            .then();
    }
}

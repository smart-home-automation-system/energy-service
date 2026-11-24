package cloud.cholewa.energy.charge;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.energy.api.model.ReserveReply;
import cloud.cholewa.energy.api.model.ReserveRequest;
import cloud.cholewa.energy.database.BatteryChargeRunEntity;
import cloud.cholewa.energy.database.BatteryChargeRunRepository;
import cloud.cholewa.energy.database.EnergyReserveEntity;
import cloud.cholewa.energy.database.EnergyReserveRepository;
import cloud.cholewa.energy.error.EnergyError;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.solax.FailureLog;
import cloud.cholewa.energy.solaxcloud.SolaxCloudException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

//The battery reserve (HAS-494): a part of the SOLAX battery the house never uses while the grid is present, held by the
//floor of the standing Self Use (SelfUseBase). The one row of energy_reserve holds the setting and what became of its
//last write - the API reads none of the settings of the inverter, so the row is the truth and nothing is written at a
//pod start.
//- A change (PUT) is stored as pending and written at once when it may be: no charge open or being ended (its end writes
//  the standing Self Use, the reserve included) and a write allowed (at most one a minute) - otherwise by the next pass.
//- Every pass (ReserveWatch, a minute): a reserve whose until was reached is switched off and told; a sent command is
//  followed as a charge is (status 4 taken; 1, 2, 5, 6 failed); a pending write is sent, a failed one again after 10
//  minutes. A failure is told once, as an alert, and its end as an info.
//Every change runs in the lane of InverterCommands, with the charges
@Slf4j
@Service
public class ReserveService {

    static final String PENDING = "pending";
    static final String SENT = "sent";
    static final String TAKEN = "taken";
    static final String FAILED = "failed";
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(6);
    private static final int EXECUTING = 4;
    private static final Set<Integer> NOT_CARRIED_OUT = Set.of(1, 2, 5, 6);
    private static final String STATUS = "result of a reserve command of the SolaX cloud";

    private final EnergyReserveRepository repository;
    private final BatteryChargeRunRepository runs;
    private final InverterCommands commands;
    private final NotificationPublisher notificationPublisher;
    private final ChargeProperties properties;
    private final Clock clock;
    private final FailureLog failureLog;
    //the charge a pending write was last noted to wait on - the INFO once a run, not every pass
    private final AtomicReference<Long> waitedOn = new AtomicReference<>();

    public ReserveService(
        final EnergyReserveRepository repository,
        final BatteryChargeRunRepository runs,
        final InverterCommands commands,
        final NotificationPublisher notificationPublisher,
        final ChargeProperties properties,
        final Clock clock
    ) {
        this.repository = repository;
        this.runs = runs;
        this.commands = commands;
        this.notificationPublisher = notificationPublisher;
        this.properties = properties;
        this.clock = clock;
        this.failureLog = new FailureLog(log, clock);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    public Mono<ReserveReply> settings() {
        return read().map(ReserveService::reply);
    }

    public Mono<ReserveReply> replace(final ReserveRequest request) {
        final ReserveRequest asked = Optional.ofNullable(request).orElse(new ReserveRequest(null, null, null));
        return commands.serially(Mono.fromCallable(this::now)
                .doOnNext(now -> ReserveRules.check(asked.enabled(), asked.level(), asked.until(), now))
                .zipWith(read())
                .flatMap(both -> repository.save(both.getT2()
                    .withEnabled(asked.enabled())
                    .withLevel(asked.level())
                    .withUntil(asked.until())
                    .withUpdatedAt(both.getT1())
                    .withWriteOutcome(PENDING)))
                .doOnNext(saved -> log.info("The battery reserve set: {}, {} %, until {}",
                    saved.enabled() ? "on" : "off", saved.level(), saved.until()))
                .flatMap(this::writeIfDue))
            .map(ReserveService::reply);
    }

    //the standing Self Use now: the reserve while it holds, the baseline otherwise
    Mono<SelfUseBase> standing() {
        return read().map(this::baseOf);
    }

    //the standing Self Use written by the end of a charge: the setting is the one now in the inverter
    Mono<Void> writtenByCharge(final String requestId) {
        return read()
            .flatMap(row -> repository.save(row.withWriteOutcome(SENT).withWriteRequestId(requestId)
                    .withWriteAttemptedAt(now()))
                .flatMap(saved -> toldWritten(row, saved)))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The write of the standing work mode {} was not recorded on the battery reserve: {}", requestId,
                failure.toString())));
    }

    //a pass of the watch: the end of a reserve set until a time, the command sent followed, the write due sent
    Mono<Void> pass() {
        return read()
            .flatMap(this::expire)
            .flatMap(this::follow)
            .flatMap(this::writeIfDue)
            .then();
    }

    private Mono<EnergyReserveEntity> expire(final EnergyReserveEntity row) {
        final LocalDateTime now = now();
        return Mono.just(row)
            .filter(held -> held.enabled() && held.until() != null && !now.isBefore(held.until()))
            .flatMap(over -> repository.save(over.withEnabled(false).withUntil(null).withUpdatedAt(now)
                    .withWriteOutcome(PENDING))
                .doOnNext(off -> log.info("The battery reserve of {} % ended, set until {}", over.level(), over.until()))
                .flatMap(off -> tell(ReserveMessages.ended(over.level(), over.until())).thenReturn(off)))
            .defaultIfEmpty(row);
    }

    private Mono<EnergyReserveEntity> follow(final EnergyReserveEntity row) {
        final LocalDateTime now = now();
        return Mono.just(row)
            .filter(held -> SENT.equals(held.writeOutcome()) && held.writeRequestId() != null
                && held.writeAttemptedAt() != null
                && now.isBefore(held.writeAttemptedAt().plus(properties.deliveryTimeout())))
            .flatMap(sent -> Mono.fromCallable(commands::serial)
                .flatMap(serial -> commands.status(sent.writeRequestId(), serial))
                .doOnNext(status -> failureLog.succeeded(STATUS))
                .onErrorResume(failure -> Mono.fromRunnable(() -> failureLog.failed(STATUS, failure))))
            .flatMap(status -> judge(row, status))
            .defaultIfEmpty(row);
    }

    private Mono<EnergyReserveEntity> judge(final EnergyReserveEntity row, final int status) {
        return Mono.just(status)
            .filter(answered -> answered == EXECUTING)
            .flatMap(executing -> repository.save(row.withWriteOutcome(TAKEN))
                .doOnNext(taken -> log.info("The standing work mode {} taken by the inverter", row.writeRequestId())))
            .switchIfEmpty(Mono.just(status)
                .filter(NOT_CARRIED_OUT::contains)
                .flatMap(failed -> failed(row, "the SolaX cloud answered status " + failed)))
            .defaultIfEmpty(row);
    }

    //the write of the standing Self Use when one is due: a change pending, or a failed one 10 minutes later; while a
    //charge is open or being ended its end writes it; a write within the minute of the last one waits for the next pass
    Mono<EnergyReserveEntity> writeIfDue(final EnergyReserveEntity row) {
        return Mono.just(row)
            .filter(this::due)
            .flatMap(due -> runs.findFirstByOrderByRequestedAtDesc()
                .filter(ReserveService::busy)
                .map(busy -> {
                    if (!busy.id().equals(waitedOn.getAndSet(busy.id()))) {
                        log.info("The battery reserve is written at the end of the charge {}", busy.id());
                    }
                    return due;
                })
                .switchIfEmpty(Mono.defer(() -> write(due))))
            .defaultIfEmpty(row);
    }

    private boolean due(final EnergyReserveEntity row) {
        return PENDING.equals(row.writeOutcome())
            || FAILED.equals(row.writeOutcome()) && (row.writeAttemptedAt() == null
            || !now().isBefore(row.writeAttemptedAt().plus(ReserveRules.RETRY_AFTER)));
    }

    //only the command decides whether the write failed - a database failure after a sent command reaches the pass, so a
    //command the inverter took is never told as not written. A registry not read yet leaves the write pending; no SOLAX
    //with a serial number in it is a failed write
    private Mono<EnergyReserveEntity> write(final EnergyReserveEntity row) {
        return Mono.fromSupplier(commands::writable)
            .filter(allowed -> allowed)
            .flatMap(allowed -> Mono.fromCallable(commands::serial))
            .flatMap(serial -> commands.restore(serial, baseOf(row))
                .map(Attempt::sent)
                .onErrorResume(failure -> Mono.just(Attempt.failed(why(failure)))))
            .flatMap(attempt -> Mono.justOrEmpty(attempt.requestId())
                .flatMap(requestId -> repository.save(row.withWriteOutcome(SENT).withWriteRequestId(requestId)
                    .withWriteAttemptedAt(now())))
                .doOnNext(saved -> log.info("The standing work mode written ({}): command {}", setting(saved),
                    saved.writeRequestId()))
                .flatMap(saved -> toldWritten(row, saved))
                .switchIfEmpty(Mono.defer(() -> failed(row, attempt.failure()))))
            .onErrorResume(EnergyException.class, refused -> Mono.just(refused)
                .filter(notNow -> notNow.getError() == EnergyError.REGISTRY_UNAVAILABLE)
                .map(notNow -> {
                    log.warn("The battery reserve could not be written yet, tried again with the next pass: {}",
                        notNow.getMessage());
                    return row;
                })
                .switchIfEmpty(Mono.defer(() -> failed(row, "no SOLAX with a serial number in the device registry"))));
    }

    //what one write of the standing Self Use came to: the request id of a command the cloud took, or why it did not
    private record Attempt(String requestId, String failure) {

        static Attempt sent(final String requestId) {
            return new Attempt(requestId, null);
        }

        static Attempt failed(final String failure) {
            return new Attempt(null, failure);
        }
    }

    private Mono<EnergyReserveEntity> failed(final EnergyReserveEntity row, final String why) {
        log.warn("The standing work mode ({}) was not written: {}", setting(row), why);
        return repository.save(row.withWriteOutcome(FAILED).withWriteRequestId(null).withWriteAttemptedAt(now()))
            .flatMap(saved -> Mono.just(row)
                .filter(before -> !FAILED.equals(before.writeOutcome()))
                .flatMap(first -> alert(ReserveMessages.notWritten(saved, why)))
                .thenReturn(saved));
    }

    //the info that a write failed before went through
    private Mono<EnergyReserveEntity> toldWritten(final EnergyReserveEntity before, final EnergyReserveEntity saved) {
        return Mono.just(before)
            .filter(row -> FAILED.equals(row.writeOutcome()))
            .flatMap(row -> tell(ReserveMessages.written(saved)))
            .thenReturn(saved);
    }

    private SelfUseBase baseOf(final EnergyReserveEntity row) {
        return commands.base(ReserveRules.inForce(row.enabled(), row.until(), now()) ? row.level() : null);
    }

    //the row the migration inserted - a database without it is a failed read, never a default
    Mono<EnergyReserveEntity> read() {
        return repository.findById(EnergyReserveEntity.ROW_ID)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("energy_reserve has no row")));
    }

    private static String why(final Throwable failure) {
        return failure instanceof SolaxCloudException
            ? "the SolaX cloud refused the command"
            : "the command could not be sent";
    }

    private static String setting(final EnergyReserveEntity row) {
        return row.enabled() ? "reserve " + row.level() + " %" : "baseline";
    }

    private Mono<Void> tell(final String text) {
        return notificationPublisher.publishInfo(text)
            .timeout(NOTICE_TIMEOUT)
            .doOnSuccess(sent -> log.info("Battery reserve notice published: {}", text))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                "The notice of the battery reserve was not published - {}: {}", failure.toString(), text)));
    }

    private Mono<Void> alert(final String text) {
        return notificationPublisher.publishAlert(text)
            .timeout(NOTICE_TIMEOUT)
            .doOnSuccess(sent -> log.info("Battery reserve alert published: {}", text))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                "The alert of the battery reserve was not published - {}: {}", failure.toString(), text)));
    }

    static ReserveReply reply(final EnergyReserveEntity row) {
        return new ReserveReply(row.enabled(), row.level(), row.until(), row.updatedAt(), row.writeOutcome(),
            row.writeAttemptedAt());
    }

    //a charge open or being ended: its end writes the standing Self Use
    private static boolean busy(final BatteryChargeRunEntity run) {
        return run.open() || run.restorePending();
    }
}

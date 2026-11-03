package cloud.cholewa.energy.charge;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.database.BatteryChargeRunEntity;
import cloud.cholewa.energy.database.BatteryChargeRunRepository;
import cloud.cholewa.energy.solax.FailureLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

//Follows the newest charge of the battery every 10 s (HAS-336), bounded at 9 s, in the lane of InverterCommands:
//- a command not yet carried out: its status asked (one WARN an hour while the cloud refuses it - 10403 to the
//  application of the owner, 2026-11-03), and the power of the battery and the grid read; 4, or the battery charging
//  from the grid at charging-above-watts - charging, told; 1 - INVERTER_UNAVAILABLE, 2 or 5 - DEVICE_FAILED,
//  both failed with nothing to set back; 6, or no 4 within the delivery timeout - DEVICE_FAILED with the baseline set
//  back (the inverter may have taken it)
//- a charge going on: ended by its target (the SOC of the battery) or its window, then the baseline set back
//- a run ended without its baseline: the baseline tried again, at most one write a minute
//Nothing is asked while the flag is lowered - no run open since the newest one was set back
@Slf4j
@Component
public class ChargeWatch {

    static final Duration LIMIT = Duration.ofSeconds(9);
    private static final String STATUS = "result of a command of the SolaX cloud";

    private final BatteryChargeRunRepository repository;
    private final InverterCommands commands;
    private final ChargeService chargeService;
    private final ChargeProperties properties;
    private final ChargeWatchFlag flag;
    private final Clock clock;
    private final FailureLog failureLog;

    public ChargeWatch(
        final BatteryChargeRunRepository repository,
        final InverterCommands commands,
        final ChargeService chargeService,
        final ChargeProperties properties,
        final ChargeWatchFlag flag,
        final Clock clock
    ) {
        this.repository = repository;
        this.commands = commands;
        this.chargeService = chargeService;
        this.properties = properties;
        this.flag = flag;
        this.clock = clock;
        this.failureLog = new FailureLog(log, clock);
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT10S")
    Mono<Void> watch() {
        return ScheduledPass.bounded("Battery charge watch", LIMIT, () -> commands.serially(Mono.defer(this::pass)));
    }

    Mono<Void> pass() {
        return Mono.fromSupplier(flag::raised)
            .filter(raised -> raised)
            .flatMap(raised -> repository.findFirstByOrderByRequestedAtDesc())
            .switchIfEmpty(Mono.fromRunnable(flag::lower))
            .flatMap(this::step)
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The battery charge could not be followed, tried again with the next pass: {}", failure.toString())));
    }

    private Mono<BatteryChargeRunEntity> step(final BatteryChargeRunEntity run) {
        final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        return switch (ChargeRules.stageOf(run)) {
            case REQUESTED -> delivery(run, now);
            case CHARGING -> chargeService.socOrNull()
                .flatMap(soc -> Mono.justOrEmpty(ChargeRules.endOf(run, soc.orElse(null), now))
                    .flatMap(by -> repository.save(run.withEndedAt(now).withEndedBy(by).withSocAtEnd(soc.orElse(null))))
                    .flatMap(this::restoreWhenAllowed));
            case ENDING -> restoreWhenAllowed(run);
            case OVER -> Mono.fromRunnable(flag::lower);
        };
    }

    private Mono<BatteryChargeRunEntity> delivery(final BatteryChargeRunEntity run, final LocalDateTime now) {
        final boolean late = !now.isBefore(run.requestedAt().plus(properties.deliveryTimeout()));
        return Mono.justOrEmpty(run.requestId())
            .flatMap(requestId -> Mono.fromCallable(commands::serial)
                .flatMap(serial -> commands.status(requestId, serial))
                .doOnNext(status -> failureLog.succeeded(STATUS))
                .onErrorResume(failure -> Mono.fromRunnable(() -> failureLog.failed(STATUS, failure))))
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .zipWith(chargeService.power())
            .flatMap(both -> decide(run, both.getT1().orElse(null), late, both.getT2(), now));
    }

    private Mono<BatteryChargeRunEntity> decide(final BatteryChargeRunEntity run, final Integer status, final boolean late,
                                                final ChargeRules.Power power, final LocalDateTime now) {
        final BatteryChargeRunEntity seen = run.withDeliveryStatus(Optional.ofNullable(status).orElse(run.deliveryStatus()));
        return switch (ChargeRules.deliveryOf(status, late, power, properties.chargingAboveWatts())) {
            case EXECUTING -> repository.save(seen.withDeliveredAt(now))
                .flatMap(charging -> chargeService.tell(ChargeMessages.started(charging)).thenReturn(charging));
            case OFFLINE -> notTaken(seen, "INVERTER_UNAVAILABLE", now);
            case NOT_TAKEN -> notTaken(seen, "DEVICE_FAILED", now);
            case UNCERTAIN -> Mono.fromRunnable(() -> log.warn("The charge {} failed: status {}, the baseline set back",
                    run.id(), status))
                .then(repository.save(seen.withEndedAt(now).withEndedBy(ChargeRules.BY_FAILURE).withFailure("DEVICE_FAILED")
                    .withSocAtEnd(power.soc())))
                .flatMap(this::restoreWhenAllowed);
            case WAITING -> Mono.just(seen)
                .filter(changed -> !Objects.equals(run.deliveryStatus(), changed.deliveryStatus()))
                .flatMap(repository::save)
                .defaultIfEmpty(run);
        };
    }

    //a command the inverter certainly did not take: the run failed, nothing to set back
    private Mono<BatteryChargeRunEntity> notTaken(final BatteryChargeRunEntity run, final String failure,
                                                  final LocalDateTime now) {
        log.warn("The charge {} failed: the SolaX cloud answered status {}", run.id(), run.deliveryStatus());
        return repository.save(run.withEndedAt(now).withEndedBy(ChargeRules.BY_FAILURE).withFailure(failure)
                .withRestoredAt(now))
            .flatMap(failed -> chargeService.tell(ChargeMessages.notStarted(failed)).thenReturn(failed));
    }

    private Mono<BatteryChargeRunEntity> restoreWhenAllowed(final BatteryChargeRunEntity run) {
        return Mono.fromSupplier(commands::writable)
            .filter(allowed -> allowed)
            .flatMap(allowed -> chargeService.restore(run))
            .defaultIfEmpty(run);
    }
}

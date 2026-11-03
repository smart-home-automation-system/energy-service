package cloud.cholewa.energy.charge;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.database.BatteryChargeRunEntity;
import cloud.cholewa.energy.database.BatteryChargeRunRepository;
import lombok.RequiredArgsConstructor;
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
//- a command not yet carried out: its status asked; 4 - charging, told; 1 - INVERTER_UNAVAILABLE, 2 or 5 - DEVICE_FAILED,
//  both failed with nothing to set back; 6, or no 4 within the delivery timeout - DEVICE_FAILED with the baseline set
//  back (the inverter may have taken it)
//- a charge going on: ended by its target (the SOC of the battery) or its window, then the baseline set back
//- a run ended without its baseline: the baseline tried again, at most one write a minute
//Nothing is asked while the flag is lowered - no run open since the newest one was set back
@Slf4j
@Component
@RequiredArgsConstructor
public class ChargeWatch {

    static final Duration LIMIT = Duration.ofSeconds(9);

    private final BatteryChargeRunRepository repository;
    private final InverterCommands commands;
    private final ChargeService chargeService;
    private final ChargeProperties properties;
    private final ChargeWatchFlag flag;
    private final Clock clock;

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
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The status of the command of the charge {} was not read: {}", run.id(), failure.getMessage()))))
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .flatMap(status -> decide(run, status.orElse(null), late, now));
    }

    private Mono<BatteryChargeRunEntity> decide(final BatteryChargeRunEntity run, final Integer status, final boolean late,
                                                final LocalDateTime now) {
        final BatteryChargeRunEntity seen = run.withDeliveryStatus(Optional.ofNullable(status).orElse(run.deliveryStatus()));
        return switch (ChargeRules.deliveryOf(status, late)) {
            case EXECUTING -> repository.save(seen.withDeliveredAt(now))
                .flatMap(charging -> chargeService.tell(ChargeMessages.started(charging)).thenReturn(charging));
            case OFFLINE -> notTaken(seen, "INVERTER_UNAVAILABLE", now);
            case NOT_TAKEN -> notTaken(seen, "DEVICE_FAILED", now);
            case UNCERTAIN -> Mono.fromRunnable(() -> log.warn("The charge {} failed: status {}, the baseline set back",
                    run.id(), status))
                .then(repository.save(seen.withEndedAt(now).withEndedBy(ChargeRules.BY_FAILURE).withFailure("DEVICE_FAILED")))
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

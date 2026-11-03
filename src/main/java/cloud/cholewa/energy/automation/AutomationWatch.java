package cloud.cholewa.energy.automation;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.charge.ChargeService;
import cloud.cholewa.energy.database.BatteryChargeRunEntity;
import cloud.cholewa.energy.database.BatteryChargeRunRepository;
import cloud.cholewa.energy.database.EnergyAutomationEntity;
import cloud.cholewa.energy.database.PlannedOutageEntity;
import cloud.cholewa.energy.database.PlannedOutageRepository;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.tauron.Announcement;
import cloud.cholewa.energy.tauron.AddressMatch;
import cloud.cholewa.energy.tauron.OutageLane;
import cloud.cholewa.energy.tauron.PlannedOutageMessages;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

//The automatic charge before a planned outage (HAS-338), a pass a minute bounded at 50 s:
//- the next planned outage of the street (owner, 2026-11-03: the ones told by SMS), still ahead and not skipped, and the SOC
//  of the battery (the dongle, the SolaX cloud while it is stale) give the planned start - held for the API every pass
//- with the switch on, from the planned start on, the charge of HAS-336 is started with source automation until the outage
//  starts - once an outage: a run linked to it (battery_charge_run.planned_outage_id) is never followed by a second, a
//  failed one included; the start told as an info by SMS, the run linked on the outage too (charge_run_id)
//- a run already going on (manual) is left alone and noted once; the SOC not known - the automation waits and tells once
//  (an alert, Discord only); a start the cloud refused - told once. The things told once are held in memory per outage
@Slf4j
@Component
@RequiredArgsConstructor
public class AutomationWatch {

    static final Duration LIMIT = Duration.ofSeconds(50);
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(6);

    private final AutomationService automationService;
    private final PlannedOutageRepository outages;
    private final BatteryChargeRunRepository runs;
    private final ChargeService chargeService;
    private final AutomationPlan plan;
    private final NotificationPublisher notificationPublisher;
    private final OutageLane outageLane;
    private final SolaxProperties solaxProperties;
    private final Clock clock;
    private final Set<String> told = ConcurrentHashMap.newKeySet();

    @Scheduled(initialDelayString = "PT45S", fixedDelayString = "PT1M")
    Mono<Void> watch() {
        return ScheduledPass.bounded("Automatic charge before an outage", LIMIT, () -> Mono.defer(this::pass));
    }

    Mono<Void> pass() {
        final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        return automationService.read()
            .flatMap(settings -> outages
                .findFirstByClosedAtIsNullAndKindAndScopeAndSkipAutomationFalseAndStartsAtAfterOrderByStartsAt(
                    Announcement.PLANNED, AddressMatch.Scope.STREET.value(), now)
                .flatMap(outage -> chargeService.power()
                    .flatMap(power -> step(settings, outage, power.soc(), now))
                    .thenReturn(outage))
                .switchIfEmpty(Mono.fromRunnable(() -> plan.set(null))))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The automatic charge before an outage could not decide, tried again with the next pass: {}",
                failure.toString())));
    }

    private Mono<Void> step(final EnergyAutomationEntity settings, final PlannedOutageEntity outage, final Double soc,
                            final LocalDateTime now) {
        final Optional<LocalDateTime> start = Optional.ofNullable(soc).flatMap(known -> AutomationRules.plannedStart(
            outage.startsAt(), known, settings.targetSoc(), settings.chargePowerKw(), settings.marginMinutes(),
            solaxProperties.batteryCapacity()));
        plan.set(new AutomationPlan.Planned(outage.id(), outage.startsAt(), start.orElse(null)));
        return switch (AutomationRules.stepOf(settings.chargeBeforeOutage(), soc, start.orElse(null), now)) {
            case OFF, WAIT, NOTHING_TO_CHARGE -> Mono.empty();
            case SOC_NOT_KNOWN -> once("soc|" + outage.id(), () -> notificationPublisher.publishAlert(
                "The automatic charge before the planned outage of " + PlannedOutageMessages.span(outage)
                    + " waits: the state of charge of the battery is not known."));
            case START -> runs.existsByPlannedOutageId(outage.id())
                .filter(started -> !started)
                .flatMap(none -> start(settings, outage));
        };
    }

    private Mono<Void> start(final EnergyAutomationEntity settings, final PlannedOutageEntity outage) {
        return chargeService.startBefore(outage.id(), settings.targetSoc(), outage.startsAt())
            .flatMap(run -> outageLane.serially(outages.setChargeRun(outage.id(), run.id())).thenReturn(run))
            .flatMap(run -> tell(notificationPublisher::publishInfoSms, started(outage, run)))
            .onErrorResume(EnergyException.class, refused -> refused(outage, refused));
    }

    private Mono<Void> refused(final PlannedOutageEntity outage, final EnergyException refused) {
        return switch (refused.getError()) {
            case WRITE_TOO_SOON, TARGET_REACHED -> Mono.empty();
            case CHARGE_IN_PROGRESS -> Mono.fromRunnable(() -> {
                if (told.add("manual|" + outage.id())) {
                    log.info("A charge already runs - the automatic charge before the outage {} leaves it alone",
                        outage.id());
                }
            });
            default -> once("refused|" + outage.id(), () -> notificationPublisher.publishAlert(
                "The automatic charge before the planned outage of " + PlannedOutageMessages.span(outage)
                    + " did not start: " + refused.getMessage() + "."));
        };
    }

    private static String started(final PlannedOutageEntity outage, final BatteryChargeRunEntity run) {
        return "Battery charging from the grid started before the planned outage of " + PlannedOutageMessages.span(outage)
            + ": SOC " + Math.round(run.socAtStart()) + " % to " + run.targetSoc() + " %.";
    }

    //told once an outage - a notice not published is tried again with the next pass
    private Mono<Void> once(final String key, final Supplier<Mono<Void>> notice) {
        return Mono.just(key)
            .filter(fresh -> !told.contains(fresh))
            .flatMap(fresh -> notice.get()
                .timeout(NOTICE_TIMEOUT)
                .then(Mono.fromRunnable(() -> told.add(fresh)))
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                    "The notice of the automatic charge was not published, tried again with the next pass: {}",
                    failure.toString()))))
            .then();
    }

    private Mono<Void> tell(final Function<String, Mono<Void>> publisher, final String text) {
        return publisher.apply(text)
            .timeout(NOTICE_TIMEOUT)
            .doOnSuccess(sent -> log.info("Automatic charge notice published: {}", text))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                "The notice of the automatic charge was not published - {}: {}", failure.toString(), text)));
    }
}

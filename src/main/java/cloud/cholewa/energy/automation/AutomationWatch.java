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
import cloud.cholewa.energy.frequency.FrequencyState;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.tauron.Announcement;
import cloud.cholewa.energy.tauron.AddressMatch;
import cloud.cholewa.energy.tauron.OutageLane;
import cloud.cholewa.energy.tauron.PlannedOutageMessages;
import jakarta.annotation.PostConstruct;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

//The automatic charge before a planned outage (HAS-338), a pass a minute bounded at 50 s:
//- the next planned outage of the street (owner, 2026-11-03: the ones told by SMS), still ahead and not skipped, and the SOC
//  of the battery (the dongle, the SolaX cloud while it is stale) give the planned start - held for the API every pass
//- with the switch on, from the planned start on, the charge of HAS-336 is started with source automation until the outage
//  starts; the start told as an info by SMS, the run linked on the outage too (charge_run_id - the latest run)
//- the runs of the outage (battery_charge_run.planned_outage_id, and the one a twin row carried - HAS-477) decide what
//  comes next (AutomationRules.attemptsOf, HAS-478): a run that ended by its target, its window or a cancel is never
//  followed by another; a failed one is, after energy.automation.retry-every, at most max-attempts runs an outage
//- the first failed run and the last attempt given up are each texted once (keyed by the run, so a row that goes on
//  under a twin tells nothing again). They are read from the stored runs, so they are told with the pass after the
//  failure; a failure that ended before this pod started is taken for told by the pod before - a restart repeats no
//  SMS (and one that failed in the last minute of that pod is lost)
//- a run going on or still being set back is left alone and noted once; the SOC not known once the latest planned
//  start is reached and a start refused before a run was made - texted once. The things told once are held in memory
//- the grid frequency off its band (HAS-503 - a generator of Tauron): the inverter does not connect, so a charge could not
//  run; the start waits, logged once an outage, and comes once the frequency is back (the frequency alert tells the rest)
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
    private final FrequencyState frequencyState;
    private final AutomationProperties properties;
    private final Clock clock;
    private final Set<String> told = ConcurrentHashMap.newKeySet();
    //the start of this pod: a failure that ended before it was told - or lost - by the pod before. Set before the
    //charge watch can end a run (its first pass comes earlier than the first one here); the first pass at the latest
    private final AtomicReference<LocalDateTime> since = new AtomicReference<>();

    @PostConstruct
    void started() {
        since.compareAndSet(null, LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
    }

    @Scheduled(initialDelayString = "PT45S", fixedDelayString = "PT1M")
    Mono<Void> watch() {
        return ScheduledPass.bounded("Automatic charge before an outage", LIMIT, () -> Mono.defer(this::pass));
    }

    Mono<Void> pass() {
        final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        since.compareAndSet(null, now);
        return automationService.read()
            .flatMap(settings -> outages
                .findFirstByClosedAtIsNullAndKindAndScopeAndSkipAutomationFalseAndStartsAtAfterOrderByStartsAtAscIdAsc(
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
        final AutomationRules.Step step = AutomationRules.stepOf(settings.chargeBeforeOutage(), soc, start.orElse(null),
            now);
        return Mono.just(step)
            .filter(on -> on != AutomationRules.Step.OFF)
            .flatMap(on -> runs.findOfOutage(outage.id(), outage.chargeRunId()).collectList())
            .flatMap(history -> adoptTwins(outage, history).then(linkLatest(outage, history)).thenReturn(history))
            .map(history -> AutomationRules.attemptsOf(history, now, properties.maxAttempts(),
                properties.retryEvery()))
            .flatMap(attempts -> tellFailures(outage, attempts)
                .then(Mono.defer(() -> act(step, attempts, settings, outage, now))));
    }

    //the runs found through the run a twin row carried (HAS-477) are linked to this row before anything else: once the
    //row has a run of its own, charge_run_id no longer leads to them. A failure ends the pass - nothing is started on a
    //history that would be lost
    private Mono<Void> adoptTwins(final PlannedOutageEntity outage, final List<BatteryChargeRunEntity> history) {
        return Flux.fromStream(() -> history.stream()
                .map(BatteryChargeRunEntity::plannedOutageId)
                .filter(linked -> linked != null && !linked.equals(outage.id()))
                .distinct())
            .concatMap(twin -> chargeService.adopt(outage.id(), twin))
            .then();
    }

    //charge_run_id of the outage is its latest run - a run the cloud refused included, which the start could not link
    private Mono<Void> linkLatest(final PlannedOutageEntity outage, final List<BatteryChargeRunEntity> history) {
        return Mono.justOrEmpty(history.stream().reduce((older, newer) -> newer))
            .map(BatteryChargeRunEntity::id)
            .filter(latest -> !latest.equals(outage.chargeRunId()))
            .flatMap(latest -> outageLane.serially(outages.setChargeRun(outage.id(), latest)))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The latest charge of the planned outage {} was not linked: {}", outage.id(), failure.toString())));
    }

    private Mono<Void> act(final AutomationRules.Step step, final AutomationRules.Attempts attempts,
                           final EnergyAutomationEntity settings, final PlannedOutageEntity outage,
                           final LocalDateTime now) {
        return switch (step) {
            case OFF, WAIT, NOTHING_TO_CHARGE -> Mono.empty();
            //texted only once even an empty battery would have to charge by now - weeks before nobody needs the word
            case SOC_NOT_KNOWN -> Mono.just(AutomationRules.latestStart(outage.startsAt(), settings.targetSoc(),
                    settings.chargePowerKw(), settings.marginMinutes(), solaxProperties.batteryCapacity()))
                .filter(latest -> !now.isBefore(latest))
                .flatMap(due -> once("soc|" + outage.id(), () -> notificationPublisher.publishAlertSms(
                    "The automatic charge before the planned outage of " + PlannedOutageMessages.span(outage)
                        + " waits: the state of charge of the battery is not known.")));
            case START -> Mono.just(attempts.attempt())
                .filter(attempt -> attempt == AutomationRules.Attempt.START)
                .flatMap(allowed -> Mono.just(frequencyState.off())
                    .filter(off -> !off)
                    .switchIfEmpty(Mono.fromRunnable(() -> waitsForFrequency(outage)))
                    .flatMap(inside -> start(settings, outage)));
        };
    }

    //the first failed run of the outage and the last attempt given up, each texted once - only a failure this pod saw
    private Mono<Void> tellFailures(final PlannedOutageEntity outage, final AutomationRules.Attempts attempts) {
        final boolean givenUp = attempts.attempt() == AutomationRules.Attempt.GIVEN_UP;
        final Mono<Void> gaveUp = Mono.justOrEmpty(attempts.lastFailed())
            .filter(last -> givenUp && !last.endedAt().isBefore(since.get()))
            .flatMap(last -> once("given up|" + last.id(), () -> notificationPublisher.publishAlertSms(
                gaveUp(outage, attempts.failed().size()))));
        final Mono<Void> failed = Mono.justOrEmpty(attempts.firstFailed())
            .filter(first -> !givenUp && !first.endedAt().isBefore(since.get()))
            .flatMap(first -> once("failed|" + first.id(), () -> notificationPublisher.publishAlertSms(
                failed(outage, first))));
        return gaveUp.then(failed);
    }

    private String failed(final PlannedOutageEntity outage, final BatteryChargeRunEntity first) {
        return "The automatic charge before the planned outage of " + PlannedOutageMessages.span(outage) + " failed ("
            + Optional.ofNullable(first.failure()).orElse("no reason stored") + ") - a failed charge is tried again after "
            + properties.retryEvery().toMinutes() + " min, at most " + properties.maxAttempts() + " runs an outage.";
    }

    private static String gaveUp(final PlannedOutageEntity outage, final int attempts) {
        return "The automatic charge before the planned outage of " + PlannedOutageMessages.span(outage)
            + " is given up after " + attempts + (attempts == 1 ? " attempt" : " attempts")
            + " - the battery is not charged, charge it by hand.";
    }

    private void waitsForFrequency(final PlannedOutageEntity outage) {
        if (told.add("frequency|" + outage.id())) {
            log.info("The grid frequency is off its band - the automatic charge before the outage {} waits for it",
                outage.id());
        }
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
            //the command the cloud refused left a failed run: told from the stored runs with the next pass, and tried again
            case DEVICE_FAILED -> Mono.empty();
            case CHARGE_IN_PROGRESS -> Mono.fromRunnable(() -> {
                if (told.add("manual|" + outage.id())) {
                    log.info("A charge runs or is still being set back - the automatic charge before the outage {} waits",
                        outage.id());
                }
            });
            default -> once("refused|" + outage.id(), () -> notificationPublisher.publishAlertSms(
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

package cloud.cholewa.energy.solax;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.registry.Inverter;
import cloud.cholewa.energy.registry.InverterRegistry;
import cloud.cholewa.home.model.InverterBrand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

//Reads the settings of the SOLAX from its dongle (HAS-518): a pass every 10 s that asks the dongle only when a read is
//due - none yet, the last attempt energy.solax.settings.interval ago, or a write of the service to the inverter
//after-write ago and not read since (the work mode and the Back Up percentages the inverter really took). Nothing is
//read while the registry has no SOLAX with an address and a registration number. A failed read keeps the last settings,
//which go stale by themselves; its WARN is one an hour per kind of failure (FailureLog). An attempt counts whether it
//succeeded or not, so a silent dongle is asked every interval, not every pass - but a pass cut off by its bound (it waited
//behind the real-time read of the dongle) is no attempt: the read is due again with the next pass
@Slf4j
@Component
public class SolaxSettingsReader {

    static final Duration LIMIT = Duration.ofSeconds(9);
    private static final String WHAT = "settings of the SOLAX";

    private final InverterRegistry inverterRegistry;
    private final SolaxClient solaxClient;
    private final SolaxSettingsTracker tracker;
    private final SolaxSettingsProperties properties;
    private final Clock clock;
    private final FailureLog failureLog;
    private final AtomicReference<Instant> attemptedAt = new AtomicReference<>();

    public SolaxSettingsReader(
        final InverterRegistry inverterRegistry,
        final SolaxClient solaxClient,
        final SolaxSettingsTracker tracker,
        final SolaxSettingsProperties properties,
        final Clock clock
    ) {
        this.inverterRegistry = inverterRegistry;
        this.solaxClient = solaxClient;
        this.tracker = tracker;
        this.properties = properties;
        this.clock = clock;
        this.failureLog = new FailureLog(log, clock);
    }

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "PT10S")
    Mono<Void> read() {
        return ScheduledPass.bounded("SOLAX settings read", LIMIT, () -> Mono.defer(this::readIfDue));
    }

    Mono<Void> readIfDue() {
        final Instant now = clock.instant();
        return Mono.just(now)
            .filter(this::due)
            .flatMap(due -> Mono.justOrEmpty(inverterRegistry.of(InverterBrand.SOLAX)))
            .filter(inverter -> inverter.address() != null && inverter.registrationNumber() != null)
            .flatMap(inverter -> {
                final Instant before = attemptedAt.getAndSet(now);
                return read(inverter).doOnCancel(() -> attemptedAt.compareAndSet(now, before));
            });
    }

    private Mono<Void> read(final Inverter inverter) {
        return solaxClient.readSetData(inverter)
            .doOnNext(tracker::read)
            .doOnNext(settings -> failureLog.succeeded(WHAT))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> failureLog.failed(WHAT, failure)));
    }

    private boolean due(final Instant now) {
        final Instant attempted = attemptedAt.get();
        return attempted == null
            || !now.isBefore(attempted.plus(properties.interval()))
            || tracker.wroteAt()
            .map(wrote -> wrote.plus(properties.afterWrite()))
            .filter(readAgainAt -> !now.isBefore(readAgainAt) && attempted.isBefore(readAgainAt))
            .isPresent();
    }
}

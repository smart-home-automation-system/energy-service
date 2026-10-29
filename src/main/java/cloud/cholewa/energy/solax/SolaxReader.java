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
import java.util.Objects;
import java.util.concurrent.TimeUnit;

//Reads the SOLAX every energy.solax.interval (HAS-328): the inverter of the brand solax from the registry, its dongle
//asked once, the reading handed to the tracker. Nothing is read while the registry has no SOLAX with an address and a
//registration number. A failed read - the dongle silent, a refused registration number, an answer for another
//inverter - marks the SOLAX unreachable and keeps its last reading; its WARN is one an hour (FailureLog). The pass is
//bounded below the shortest interval; the call alone takes at most 3 s to connect and 5 s to answer
@Slf4j
@Component
public class SolaxReader {

    static final Duration LIMIT = Duration.ofSeconds(9);
    private static final String WHAT = "SOLAX";

    private final InverterRegistry inverterRegistry;
    private final SolaxClient solaxClient;
    private final SolaxTracker solaxTracker;
    private final FailureLog failureLog;
    private final FailureLog registryLog;

    public SolaxReader(
        final InverterRegistry inverterRegistry,
        final SolaxClient solaxClient,
        final SolaxTracker solaxTracker,
        final Clock clock
    ) {
        this.inverterRegistry = inverterRegistry;
        this.solaxClient = solaxClient;
        this.solaxTracker = solaxTracker;
        this.failureLog = new FailureLog(log, clock);
        this.registryLog = new FailureLog(log, clock);
    }

    //timeUnit: a bare number of energy.solax.interval is seconds here too, as SolaxProperties binds it
    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "${energy.solax.interval}", timeUnit = TimeUnit.SECONDS)
    Mono<Void> read() {
        return ScheduledPass.bounded("SOLAX read", LIMIT, () -> Mono.defer(() -> Mono.justOrEmpty(
                inverterRegistry.of(InverterBrand.SOLAX)))
            .filter(SolaxReader::reachableInRegistry)
            .switchIfEmpty(Mono.fromRunnable(this::notInRegistry))
            .flatMap(this::read));
    }

    private Mono<Void> read(final Inverter inverter) {
        registryLog.succeeded("SOLAX of the registry");
        return solaxClient.read(inverter)
            .filter(reading -> Objects.equals(reading.inverterSerial(), inverter.serialNumber()))
            .switchIfEmpty(Mono.error(() -> new SolaxCallException(
                "The dongle answers for another inverter than " + inverter.serialNumber() + " of the registry")))
            .doOnNext(reading -> solaxTracker.read(inverter.id(), reading))
            .doOnNext(reading -> failureLog.succeeded(WHAT))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> {
                solaxTracker.failed();
                failureLog.failed(WHAT, failure);
            }));
    }

    //the inverters of the registry are not known yet, or none is a SOLAX with what its dongle needs - then the SOLAX
    //is not reachable either: a last reading is kept, served as stale
    private void notInRegistry() {
        inverterRegistry.current().ifPresent(known -> {
            solaxTracker.failed();
            registryLog.failed("SOLAX of the registry", new SolaxCallException(
                "No switched-on SOLAX with an address and a registration number is registered"));
        });
    }

    private static boolean reachableInRegistry(final Inverter inverter) {
        return inverter.address() != null && inverter.registrationNumber() != null;
    }
}

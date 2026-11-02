package cloud.cholewa.energy.sofar;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.registry.Inverter;
import cloud.cholewa.energy.registry.InverterRegistry;
import cloud.cholewa.energy.solax.FailureLog;
import cloud.cholewa.home.model.InverterBrand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

//Reads the SOFAR from the Solarman cloud every energy.sofar.cloud.interval (HAS-330): the inverter of the brand sofar from
//the registry, asked by its serial number; nothing is read while the registry has no SOFAR with a serial. An answer of
//another device or without data is a failed read; a failed read marks the SOFAR unreachable and keeps its last reading,
//one WARN an hour per kind of failure (FailureLog). After every reading the fault watch decides on its faults (in the
//same bounded pass). One call in five minutes - far below the 50 a minute of the API
@Slf4j
@Component
public class SofarReader {

    static final Duration LIMIT = Duration.ofSeconds(50);
    private static final String WHAT = "Solarman cloud";

    private final InverterRegistry inverterRegistry;
    private final SolarmanClient solarmanClient;
    private final SofarTracker sofarTracker;
    private final SofarFaultWatch sofarFaultWatch;
    private final SolarmanProperties properties;
    private final Clock clock;
    private final FailureLog failureLog;

    public SofarReader(
        final InverterRegistry inverterRegistry,
        final SolarmanClient solarmanClient,
        final SofarTracker sofarTracker,
        final SofarFaultWatch sofarFaultWatch,
        final SolarmanProperties properties,
        final Clock clock
    ) {
        this.inverterRegistry = inverterRegistry;
        this.solarmanClient = solarmanClient;
        this.sofarTracker = sofarTracker;
        this.sofarFaultWatch = sofarFaultWatch;
        this.properties = properties;
        this.clock = clock;
        this.failureLog = new FailureLog(log, clock);
    }

    //timeUnit: a bare number of the interval is minutes here too, as SolarmanProperties binds it
    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "${energy.sofar.cloud.interval}", timeUnit = TimeUnit.MINUTES)
    Mono<Void> read() {
        return ScheduledPass.bounded("SOFAR read", LIMIT, () -> Mono.defer(() -> Mono.justOrEmpty(
                inverterRegistry.of(InverterBrand.SOFAR)))
            .filter(inverter -> inverter.serialNumber() != null)
            .map(this::read)
            .defaultIfEmpty(Mono.defer(this::goneFromTheRegistry))
            .flatMap(Function.identity()));
    }

    //a registry that was read and has no SOFAR with a serial: the last reading is kept, the SOFAR no longer reachable - as
    //the SOLAX; while the registry is not known nothing changes
    private Mono<Void> goneFromTheRegistry() {
        return Mono.justOrEmpty(inverterRegistry.current())
            .filter(read -> sofarTracker.current().reading() != null)
            .doOnNext(read -> sofarTracker.failed(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)))
            .then();
    }

    //the faults are decided right after each reading, so none is replaced by the next read unseen; a read cut off by the
    //bound of the pass is a failed read too - the cut after the reading (in the fault watch) is not
    private Mono<Void> read(final Inverter inverter) {
        final AtomicBoolean arrived = new AtomicBoolean();
        return solarmanClient.currentData(inverter.serialNumber())
            .map(answer -> SofarReading.of(answer, inverter.serialNumber(), clock.getZone()))
            .doOnNext(reading -> {
                arrived.set(true);
                final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
                sofarTracker.read(inverter.id(), reading, now,
                    !reading.collectedAt().isBefore(now.minus(properties.staleAfter())));
                failureLog.succeeded(WHAT);
            })
            //the cancel of the bound reaches this point after the reading too (flatMap cancels its source): only a read
            //that brought nothing is a failed one
            .doOnCancel(() -> {
                if (!arrived.get()) {
                    sofarTracker.failed(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
                }
            })
            .flatMap(reading -> sofarFaultWatch.check())
            .onErrorResume(failure -> Mono.fromRunnable(() -> {
                sofarTracker.failed(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
                failureLog.failed(WHAT, failure);
            }));
    }
}

package cloud.cholewa.energy.solaxcloud;

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
import java.util.Optional;
import java.util.concurrent.TimeUnit;

//Reads the SOLAX and its battery from the SolaX cloud every energy.solax.cloud.interval (HAS-329): the backup of the
//dongle - the API serves it while the dongle is unreachable - and what GET /status says of the cloud. The inverter of
//the brand solax from the registry, asked by its serial number - its battery too, by the serial of the inverter; nothing
//is read while the registry has no serial. A failed read marks the cloud unreachable and keeps the last answers; its WARN is one an
//hour per kind of failure (FailureLog). Two calls in five minutes - far below the 100 a minute of the application
@Slf4j
@Component
public class SolaxCloudReader {

    static final Duration LIMIT = Duration.ofSeconds(50);
    private static final String WHAT = "SolaX cloud";
    private static final String PLANT = "SolaX cloud plant";

    private final InverterRegistry inverterRegistry;
    private final SolaxCloudClient solaxCloudClient;
    private final SolaxCloudTracker solaxCloudTracker;
    private final CloudDays cloudDays;
    private final Clock clock;
    private final FailureLog failureLog;
    private final FailureLog plantFailureLog;

    public SolaxCloudReader(
        final InverterRegistry inverterRegistry,
        final SolaxCloudClient solaxCloudClient,
        final SolaxCloudTracker solaxCloudTracker,
        final CloudDays cloudDays,
        final Clock clock
    ) {
        this.inverterRegistry = inverterRegistry;
        this.solaxCloudClient = solaxCloudClient;
        this.solaxCloudTracker = solaxCloudTracker;
        this.cloudDays = cloudDays;
        this.clock = clock;
        this.failureLog = new FailureLog(log, clock);
        this.plantFailureLog = new FailureLog(log, clock);
    }

    //timeUnit: a bare number of the interval is minutes here too, as SolaxCloudProperties binds it
    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "${energy.solax.cloud.interval}", timeUnit = TimeUnit.MINUTES)
    Mono<Void> read() {
        return ScheduledPass.bounded("SolaX cloud read", LIMIT, () -> Mono.defer(() -> Mono.justOrEmpty(
                inverterRegistry.of(InverterBrand.SOLAX)))
            .filter(inverter -> inverter.serialNumber() != null)
            .flatMap(this::read));
    }

    //each call with its own error handling: a failed battery read keeps a good inverter answer, and the other way; the
    //plant (HAS-331, the cross-check of the days) neither makes the cloud reachable nor unreachable, and logs apart
    private Mono<Void> read(final Inverter inverter) {
        return Mono.zip(
                answer(solaxCloudClient.inverter(inverter.serialNumber())),
                answer(solaxCloudClient.battery(inverter.serialNumber())),
                plant())
            .doOnNext(answers -> {
                answers.getT1().ifPresent(cloudDays::inverter);
                answers.getT3().ifPresent(cloudDays::plant);
                final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
                if (answers.getT1().isEmpty() && answers.getT2().isEmpty()) {
                    solaxCloudTracker.failed();
                } else {
                    solaxCloudTracker.read(answers.getT1().orElse(null), answers.getT2().orElse(null), now);
                }
                if (answers.getT1().isPresent() && answers.getT2().isPresent()) {
                    failureLog.succeeded(WHAT);
                }
            })
            .then();
    }

    private Mono<Optional<CloudPlant>> plant() {
        return solaxCloudClient.plant()
            .map(Optional::of)
            .doOnNext(answer -> plantFailureLog.succeeded(PLANT))
            .onErrorResume(failure -> Mono.fromRunnable(() -> plantFailureLog.failed(PLANT, failure))
                .then(Mono.just(Optional.empty())));
    }

    private <T> Mono<Optional<T>> answer(final Mono<T> call) {
        return call.map(Optional::of)
            .onErrorResume(failure -> Mono.fromRunnable(() -> failureLog.failed(WHAT, failure))
                .then(Mono.just(Optional.empty())));
    }
}

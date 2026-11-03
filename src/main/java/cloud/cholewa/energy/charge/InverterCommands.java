package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.history.OneAtATime;
import cloud.cholewa.energy.registry.Inverter;
import cloud.cholewa.energy.registry.InverterRegistry;
import cloud.cholewa.energy.solaxcloud.SolaxCloudClient;
import cloud.cholewa.home.model.InverterBrand;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

//The commands to the SOLAX through the SolaX cloud (HAS-336) and the one lane every change of a charge runs in: a request
//of the API and the pass of the watch never change a run at once (OneAtATime). At most one write a minute (the task):
//every attempt counts, a refused one included - a second within the minute is WRITE_TOO_SOON. The inverter is the SOLAX
//of the device registry, by its serial number
@Component
public class InverterCommands {

    static final Duration WRITE_AT_MOST_EVERY = Duration.ofMinutes(1);

    private final SolaxCloudClient client;
    private final InverterRegistry inverterRegistry;
    private final ChargeProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final OneAtATime lane = new OneAtATime();
    private final AtomicReference<Instant> lastWriteAt = new AtomicReference<>();

    public InverterCommands(
        final SolaxCloudClient client,
        final InverterRegistry inverterRegistry,
        final ChargeProperties properties,
        final JsonMapper jsonMapper,
        final Clock clock
    ) {
        this.client = client;
        this.inverterRegistry = inverterRegistry;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    //the work, after any change of a charge started before it has ended
    public <T> Mono<T> serially(final Mono<T> work) {
        return lane.run(work);
    }

    //whether a write may go now
    boolean writable() {
        return Optional.ofNullable(lastWriteAt.get())
            .map(last -> !clock.instant().isBefore(last.plus(WRITE_AT_MOST_EVERY)))
            .orElse(true);
    }

    //the serial number of the SOLAX of the registry - REGISTRY_UNAVAILABLE before it was read, NOT_FOUND_INVERTER without
    //a SOLAX with a serial number
    String serial() {
        if (inverterRegistry.current().isEmpty()) {
            throw EnergyException.registryUnavailable();
        }
        return inverterRegistry.of(InverterBrand.SOLAX)
            .map(Inverter::serialNumber)
            .filter(serial -> !serial.isBlank())
            .orElseThrow(() -> EnergyException.inverterNotFound("solax"));
    }

    //the charge of a run: the request id of the command
    Mono<String> charge(final String serial, final int targetSoc, final LocalDateTime start, final LocalDateTime end) {
        return write(() -> client.setSelfUse(SelfUseCommand.charge(jsonMapper, serial, properties.baseline(), targetSoc,
            start, end)));
    }

    //the baseline of Self Use: the request id of the command
    Mono<String> restore(final String serial) {
        return write(() -> client.setSelfUse(SelfUseCommand.baseline(jsonMapper, serial, properties.baseline())));
    }

    //the status of a command for the SOLAX, empty while the cloud has none
    Mono<Integer> status(final String requestId, final String serial) {
        return client.requestStatus(requestId, serial);
    }

    private Mono<String> write(final Supplier<Mono<String>> command) {
        return Mono.fromSupplier(this::writable)
            .filter(allowed -> allowed)
            .switchIfEmpty(Mono.error(EnergyException::writeTooSoon))
            .doOnNext(allowed -> lastWriteAt.set(clock.instant()))
            .flatMap(allowed -> command.get());
    }
}

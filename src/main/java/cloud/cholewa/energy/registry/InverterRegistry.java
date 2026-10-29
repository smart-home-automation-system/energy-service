package cloud.cholewa.energy.registry;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.home.model.InverterBrand;
import cloud.cholewa.home.model.SmartDevice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//The inverters of the device registry (HAS-328), read at the start and every five minutes. A failed read is a WARN
//and keeps what was read before; until the first read succeeds nothing is known (the API answers
//REGISTRY_UNAVAILABLE). A device the service does not understand is a WARN of every read - the registry never
//stores one
@Slf4j
@Component
@RequiredArgsConstructor
public class InverterRegistry {

    static final Duration LIMIT = Duration.ofMinutes(1);

    private final DeviceRegistryClient deviceRegistryClient;
    private final AtomicReference<List<Inverter>> inverters = new AtomicReference<>();

    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "PT5M")
    Mono<Void> refresh() {
        return ScheduledPass.bounded("inverter registry refresh", LIMIT, () -> deviceRegistryClient.getInverters()
            .map(InverterRegistry::understood)
            .doOnNext(this::replace)
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() ->
                log.warn("The inverters of the registry could not be read, the last ones are kept: {}", failure.toString()))));
    }

    //the inverters of the registry, or nothing before the first read succeeded
    public Optional<List<Inverter>> current() {
        return Optional.ofNullable(inverters.get());
    }

    //the inverter of a brand - the house has one of each - or nothing while it is not known
    public Optional<Inverter> of(final InverterBrand brand) {
        return current().flatMap(known -> known.stream().filter(inverter -> inverter.brand() == brand).findFirst());
    }

    private void replace(final List<Inverter> read) {
        final List<Inverter> before = inverters.getAndSet(read);
        if (!read.equals(before)) {
            log.info("The registry knows {} inverter(s): {}", read.size(),
                read.stream().map(inverter -> inverter.brand().getValue() + " " + inverter.id()).toList());
        }
    }

    private static List<Inverter> understood(final List<SmartDevice> devices) {
        return devices.stream()
            .map(device -> Inverter.of(device).or(() -> {
                log.warn("Device {} of the registry is no inverter the service understands", device.getId());
                return Optional.empty();
            }))
            .flatMap(Optional::stream)
            .toList();
    }
}

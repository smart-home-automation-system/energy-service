package cloud.cholewa.energy.inverter;

import cloud.cholewa.energy.api.model.InverterReply;
import cloud.cholewa.energy.api.model.PhaseReply;
import cloud.cholewa.energy.api.model.PvReply;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.registry.Inverter;
import cloud.cholewa.energy.registry.InverterRegistry;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.solax.SolaxReading;
import cloud.cholewa.energy.solax.SolaxState;
import cloud.cholewa.energy.solax.SolaxTracker;
import cloud.cholewa.home.model.InverterBrand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

//The inverters as GET /home/energy/inverters and /inverters/{id} answer them (HAS-328): every inverter of the registry,
//by its brand as the id; the SOLAX with its reading from the state in memory - the dongle is never asked for a request.
//The SOFAR answers its registry entry until its task (HAS-330). Before the registry was read once the answer is
//REGISTRY_UNAVAILABLE. "Now" is read inside the chain
@Service
@RequiredArgsConstructor
public class InverterService {

    private final InverterRegistry inverterRegistry;
    private final SolaxTracker solaxTracker;
    private final SolaxProperties solaxProperties;
    private final Clock clock;

    public Mono<List<InverterReply>> inverters() {
        return registered()
            .map(inverters -> inverters.stream().map(inverter -> replyOf(inverter, LocalDateTime.now(clock))).toList());
    }

    public Mono<InverterReply> inverter(final String id) {
        return Mono.justOrEmpty(Arrays.stream(InverterBrand.values()).filter(brand -> brand.getValue().equals(id))
                .findFirst())
            .switchIfEmpty(Mono.error(() -> EnergyException.inverterNotFound(id)))
            .flatMap(brand -> registered().flatMap(inverters -> Mono.justOrEmpty(inverters.stream()
                .filter(inverter -> inverter.brand() == brand).findFirst())))
            .switchIfEmpty(Mono.error(() -> EnergyException.inverterNotFound(id)))
            .map(inverter -> replyOf(inverter, LocalDateTime.now(clock)));
    }

    private Mono<List<Inverter>> registered() {
        return Mono.defer(() -> Mono.justOrEmpty(inverterRegistry.current()))
            .switchIfEmpty(Mono.error(EnergyException::registryUnavailable));
    }

    InverterReply replyOf(final Inverter inverter, final LocalDateTime now) {
        final SolaxState state = solaxTracker.current();
        return inverter.brand() == InverterBrand.SOLAX && state.reading() != null
            && Long.valueOf(inverter.id()).equals(state.inverterId())
            ? solax(inverter, state, now)
            : registered(inverter);
    }

    private InverterReply solax(final Inverter inverter, final SolaxState state, final LocalDateTime now) {
        final SolaxReading reading = state.reading();
        return new InverterReply(inverter.brand().getValue(), inverter.id(), inverter.name(), room(inverter),
            reading.runMode(), reading.acPower(), phases(reading), reading.pvPower(), pv(reading),
            reading.gridPower(),
            reading.frequency(), reading.powerFactor(),
            reading.temperature(), reading.dailyYield(), reading.totalYield(), state.readingAt(),
            state.isStale(now, solaxProperties.staleAfter()));
    }

    private static InverterReply registered(final Inverter inverter) {
        return new InverterReply(inverter.brand().getValue(), inverter.id(), inverter.name(), room(inverter),
            null, null, null, null, null, null, null, null, null, null, null, null, true);
    }

    private static String room(final Inverter inverter) {
        return inverter.room() == null ? null : inverter.room().getValue();
    }

    private static Map<String, PhaseReply> phases(final SolaxReading reading) {
        final Map<String, PhaseReply> phases = new LinkedHashMap<>();
        phases.put("a", phase(reading.a()));
        phases.put("b", phase(reading.b()));
        phases.put("c", phase(reading.c()));
        return phases;
    }

    private static Map<String, PvReply> pv(final SolaxReading reading) {
        final Map<String, PvReply> pv = new LinkedHashMap<>();
        pv.put("pv1", pv(reading.pv1()));
        pv.put("pv2", pv(reading.pv2()));
        return pv;
    }

    private static PvReply pv(final SolaxReading.Pv string) {
        return new PvReply(string.voltage(), string.current(), string.power());
    }

    private static PhaseReply phase(final SolaxReading.Phase phase) {
        return new PhaseReply(phase.voltage(), phase.current(), phase.power(), null, null, null);
    }
}

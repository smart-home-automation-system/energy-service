package cloud.cholewa.energy.inverter;

import cloud.cholewa.energy.api.model.InverterReply;
import cloud.cholewa.energy.api.model.PhaseReply;
import cloud.cholewa.energy.api.model.PvReply;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.registry.Inverter;
import cloud.cholewa.energy.registry.InverterRegistry;
import cloud.cholewa.energy.sofar.SofarReading;
import cloud.cholewa.energy.sofar.SofarState;
import cloud.cholewa.energy.sofar.SofarTracker;
import cloud.cholewa.energy.sofar.SolarmanProperties;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.solax.SolaxReading;
import cloud.cholewa.energy.solax.SolaxState;
import cloud.cholewa.energy.solax.SolaxTracker;
import cloud.cholewa.energy.solaxcloud.CloudInverter;
import cloud.cholewa.energy.solaxcloud.SolaxCloudProperties;
import cloud.cholewa.energy.solaxcloud.SolaxCloudState;
import cloud.cholewa.energy.solaxcloud.SolaxCloudTracker;
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
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

//The inverters as GET /home/energy/inverters and /inverters/{id} answer them (HAS-328): every inverter of the registry,
//by its brand as the id; the SOLAX with its reading from the state in memory - the dongle is never asked for a request.
//The SOFAR with its reading from the Solarman cloud (HAS-330), its registry entry and stale before the first. Before the registry was read once the answer is
//REGISTRY_UNAVAILABLE. "Now" is read inside the chain. While the dongle is stale the SOLAX answers what the SolaX cloud
//read last, when it is of the same inverter and fresh (HAS-329), with source cloud
@Service
@RequiredArgsConstructor
public class InverterService {

    static final String LOCAL = "local";
    static final String CLOUD = "cloud";
    static final String SOLARMAN_CLOUD = "solarman-cloud";

    private final InverterRegistry inverterRegistry;
    private final SolaxTracker solaxTracker;
    private final SolaxProperties solaxProperties;
    private final SolaxCloudTracker solaxCloudTracker;
    private final SolaxCloudProperties solaxCloudProperties;
    private final SofarTracker sofarTracker;
    private final SolarmanProperties solarmanProperties;
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
        if (inverter.brand() == InverterBrand.SOFAR) {
            final SofarState sofar = sofarTracker.current();
            return sofar.reading() != null && Long.valueOf(inverter.id()).equals(sofar.inverterId())
                ? sofar(inverter, sofar, now)
                : registered(inverter);
        }
        final SolaxState state = solaxTracker.current();
        final boolean local = inverter.brand() == InverterBrand.SOLAX && state.reading() != null
            && Long.valueOf(inverter.id()).equals(state.inverterId());
        if (local && !state.isStale(now, solaxProperties.staleAfter())) {
            return solax(inverter, state, now);
        }
        return cloud(inverter, now, local ? state.readingAt() : null)
            .orElseGet(() -> local ? solax(inverter, state, now) : registered(inverter));
    }

    //the SolaX cloud for the SOLAX while the dongle is stale: its last answer, when it is of this inverter, fresh and newer
    //than the reading of the dongle
    private Optional<InverterReply> cloud(final Inverter inverter, final LocalDateTime now,
                                          final LocalDateTime dongleReadingAt) {
        final SolaxCloudState cloud = solaxCloudTracker.current();
        return Optional.ofNullable(cloud.inverter())
            .filter(answer -> inverter.brand() == InverterBrand.SOLAX)
            .filter(answer -> Objects.equals(answer.deviceSn(), inverter.serialNumber()))
            .filter(answer -> cloud.fresh(answer.plantLocalTime(), now, solaxCloudProperties.staleAfter(),
                dongleReadingAt))
            .map(answer -> cloud(inverter, answer));
    }

    private static InverterReply cloud(final Inverter inverter, final CloudInverter answer) {
        final Map<String, PhaseReply> phases = new LinkedHashMap<>();
        phases.put("a", new PhaseReply(answer.acVoltage1(), answer.acCurrent1(), answer.acPower1(), null, null, null));
        phases.put("b", new PhaseReply(answer.acVoltage2(), answer.acCurrent2(), answer.acPower2(), null, null, null));
        phases.put("c", new PhaseReply(answer.acVoltage3(), answer.acCurrent3(), answer.acPower3(), null, null, null));
        final Map<String, Double> mppt = Optional.ofNullable(answer.mpptMap()).orElse(Map.of());
        final Map<String, PvReply> pv = new LinkedHashMap<>();
        pv.put("pv1", new PvReply(mppt.get("MPPT1Voltage"), mppt.get("MPPT1Current"), mppt.get("MPPT1Power")));
        pv.put("pv2", new PvReply(mppt.get("MPPT2Voltage"), mppt.get("MPPT2Current"), mppt.get("MPPT2Power")));
        final Double acPower = Stream.of(answer.acPower1(), answer.acPower2(), answer.acPower3()).anyMatch(Objects::isNull)
            ? null : answer.acPower1() + answer.acPower2() + answer.acPower3();
        return new InverterReply(inverter.brand().getValue(), inverter.id(), inverter.name(), room(inverter), null,
            null, null, acPower, phases, answer.mpptTotalInputPower(), pv, null, answer.acFrequency1(), answer.totalPowerFactor(),
            answer.inverterTemperature(), answer.dailyYield(), answer.totalYield(),
            SolaxCloudState.plantTime(answer.plantLocalTime()), false, CLOUD);
    }

    private InverterReply solax(final Inverter inverter, final SolaxState state, final LocalDateTime now) {
        final SolaxReading reading = state.reading();
        return new InverterReply(inverter.brand().getValue(), inverter.id(), inverter.name(), room(inverter),
            reading.runMode(), null, null, reading.acPower(), phases(reading), reading.pvPower(), pv(reading),
            reading.gridPower(),
            reading.frequency(), reading.powerFactor(),
            reading.temperature(), reading.dailyYield(), reading.totalYield(), state.readingAt(),
            state.isStale(now, solaxProperties.staleAfter()), LOCAL);
    }

    private static InverterReply registered(final Inverter inverter) {
        return new InverterReply(inverter.brand().getValue(), inverter.id(), inverter.name(), room(inverter),
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, true, null);
    }

    //the SOFAR from the Solarman cloud (HAS-330): its last reading, when it is of this inverter - stale once it was
    //collected longer ago than the bound or the last read failed
    private InverterReply sofar(final Inverter inverter, final SofarState state, final LocalDateTime now) {
        final SofarReading reading = state.reading();
        final Map<String, PhaseReply> phases = new LinkedHashMap<>();
        phases.put("a", phase(reading.a()));
        phases.put("b", phase(reading.b()));
        phases.put("c", phase(reading.c()));
        final Map<String, PvReply> pv = new LinkedHashMap<>();
        pv.put("pv1", new PvReply(reading.pv1().voltage(), reading.pv1().current(), reading.pv1().power()));
        pv.put("pv2", new PvReply(reading.pv2().voltage(), reading.pv2().current(), reading.pv2().power()));
        return new InverterReply(inverter.brand().getValue(), inverter.id(), inverter.name(), room(inverter), null,
            reading.state(), reading.faults(), reading.acPower(), phases, reading.pvPower(), pv, null,
            reading.frequency(), null, reading.temperature(), reading.dailyYield(), reading.totalYield(),
            reading.collectedAt(), state.isStale(now, solarmanProperties.staleAfter()), SOLARMAN_CLOUD);
    }

    private static PhaseReply phase(final SofarReading.Phase phase) {
        return new PhaseReply(phase.voltage(), phase.current(), phase.power(), null, null, null);
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

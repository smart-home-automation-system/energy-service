package cloud.cholewa.energy.solax;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.database.BatteryReadingEntity;
import cloud.cholewa.energy.database.BatteryReadingRepository;
import cloud.cholewa.energy.database.InverterReadingEntity;
import cloud.cholewa.energy.database.InverterReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//A row of inverter_reading and one of battery_reading a minute (HAS-328), each only when one of its values changed
//since the last row written - a SOLAX that fell silent writes nothing more. Nothing is written before the first
//reading. The last rows are remembered in memory: after a start the first ones are written whatever the database
//holds. A failed write is a WARN and is tried again with the next minute. The two inserts go one after the other, on
//one connection at a time, never while a device is asked
@Slf4j
@Component
@RequiredArgsConstructor
public class SolaxSnapshot {

    static final Duration LIMIT = Duration.ofSeconds(50);

    private final SolaxTracker solaxTracker;
    private final InverterReadingRepository inverterReadingRepository;
    private final BatteryReadingRepository batteryReadingRepository;
    private final AtomicReference<InverterReadingEntity> lastInverter = new AtomicReference<>();
    private final AtomicReference<BatteryReadingEntity> lastBattery = new AtomicReference<>();

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    Mono<Void> snapshot() {
        return ScheduledPass.bounded("SOLAX snapshot", LIMIT, () -> Mono.fromSupplier(solaxTracker::current)
            .flatMap(state -> inverter(state).then(battery(state))));
    }

    private Mono<Void> inverter(final SolaxState state) {
        return Mono.justOrEmpty(inverterRow(state))
            .filter(row -> !sameValues(row, lastInverter.get()))
            .flatMap(row -> inverterReadingRepository.save(row)
                .doOnNext(written -> lastInverter.set(row))
                .onErrorResume(failure -> failed("inverter", failure)))
            .then();
    }

    private Mono<Void> battery(final SolaxState state) {
        return Mono.justOrEmpty(batteryRow(state))
            .filter(row -> !sameValues(row, lastBattery.get()))
            .flatMap(row -> batteryReadingRepository.save(row)
                .doOnNext(written -> lastBattery.set(row))
                .onErrorResume(failure -> failed("battery", failure)))
            .then();
    }

    private static <T> Mono<T> failed(final String what, final Throwable failure) {
        return Mono.fromRunnable(() -> log.warn("The {} snapshot of the SOLAX could not be written: {}", what,
            failure.toString()));
    }

    //the row of a state, or nothing while no reading came
    static Optional<InverterReadingEntity> inverterRow(final SolaxState state) {
        return Optional.ofNullable(state.reading()).map(reading -> new InverterReadingEntity(null,
            state.inverterId(), state.readingAt(), reading.pvPower(), reading.pv1().power(), reading.pv2().power(),
            reading.acPower(),
            Optional.ofNullable(reading.runMode()).map(RunMode::wireName).orElse(null),
            reading.temperature(), reading.dailyYield()));
    }

    static Optional<BatteryReadingEntity> batteryRow(final SolaxState state) {
        return Optional.ofNullable(state.reading()).map(SolaxReading::battery).map(battery ->
            new BatteryReadingEntity(null, state.readingAt(), battery.soc(), battery.power(), battery.voltage(),
                battery.current(), battery.temperature()));
    }

    //the values, not the time: a reading repeated unchanged writes no row
    private static boolean sameValues(final InverterReadingEntity row, final InverterReadingEntity last) {
        return last != null && row.equals(new InverterReadingEntity(null, last.inverterId(), row.measuredAt(),
            last.pvPower(), last.pv1Power(), last.pv2Power(), last.acPower(), last.runMode(), last.temperature(),
            last.dailyYield()));
    }

    private static boolean sameValues(final BatteryReadingEntity row, final BatteryReadingEntity last) {
        return last != null && row.equals(new BatteryReadingEntity(null, row.measuredAt(), last.soc(), last.power(),
            last.voltage(), last.current(), last.temperature()));
    }
}

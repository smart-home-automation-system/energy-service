package cloud.cholewa.energy.sofar;

import cloud.cholewa.commons.scheduling.ScheduledPass;
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

//A row of inverter_reading for the SOFAR a minute (HAS-330), only when one of its values changed since the last row
//written - the cloud brings a new reading every five minutes, a logger that fell silent writes nothing more. Its time is
//the time the logger collected the reading; run_mode stays NULL - the state of the SOFAR comes in the words of the cloud,
//not as one of the run modes of the SOLAX. The last row is remembered in memory, as in SolaxSnapshot; a failed write is a
//WARN and is tried again with the next minute
@Slf4j
@Component
@RequiredArgsConstructor
public class SofarSnapshot {

    static final Duration LIMIT = Duration.ofSeconds(50);

    private final SofarTracker sofarTracker;
    private final InverterReadingRepository inverterReadingRepository;
    private final AtomicReference<InverterReadingEntity> last = new AtomicReference<>();

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    Mono<Void> snapshot() {
        return ScheduledPass.bounded("SOFAR snapshot", LIMIT, () -> Mono.defer(() -> Mono.justOrEmpty(
                row(sofarTracker.current())))
            .filter(row -> !sameValues(row, last.get()))
            .flatMap(row -> inverterReadingRepository.save(row)
                .doOnNext(written -> last.set(row))
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The snapshot of the SOFAR could not be written: {}", failure.toString()))))
            .then());
    }

    //the row of a state, or nothing while no reading came
    static Optional<InverterReadingEntity> row(final SofarState state) {
        return Optional.ofNullable(state.reading()).map(reading -> new InverterReadingEntity(null, state.inverterId(),
            reading.collectedAt(), reading.pvPower(), reading.pv1().power(), reading.pv2().power(), reading.acPower(),
            null, reading.temperature(), reading.dailyYield()));
    }

    //the values, not the time: a reading repeated unchanged writes no row
    private static boolean sameValues(final InverterReadingEntity row, final InverterReadingEntity last) {
        return last != null && row.equals(new InverterReadingEntity(null, last.inverterId(), row.measuredAt(),
            last.pvPower(), last.pv1Power(), last.pv2Power(), last.acPower(), last.runMode(), last.temperature(),
            last.dailyYield()));
    }
}

package cloud.cholewa.energy.meter;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.database.GridReadingEntity;
import cloud.cholewa.energy.database.GridReadingRepository;
import cloud.cholewa.shelly.model.EmDataStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//A row of grid_reading a minute (HAS-327), only when a value changed since the last row written - a meter that
//fell silent writes nothing more. Nothing is written before the first readings. The last row is remembered in
//memory: after a start the first snapshot is written whatever the database holds. A failed write is a WARN and is
//tried again with the next minute. The pass holds one connection for one insert, never while a device is asked
@Slf4j
@Component
@RequiredArgsConstructor
public class GridSnapshot {

    static final Duration LIMIT = Duration.ofSeconds(50);
    private static final int WH_PER_KWH_DIGITS = 3;

    private final GridMeterTracker gridMeterTracker;
    private final GridReadingRepository gridReadingRepository;
    private final AtomicReference<GridReadingEntity> lastWritten = new AtomicReference<>();

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    Mono<Void> snapshot() {
        return ScheduledPass.bounded("grid meter snapshot", LIMIT, () -> Mono.fromSupplier(gridMeterTracker::current)
            .flatMap(state -> Mono.justOrEmpty(rowOf(state)))
            .filter(row -> !sameValues(row, lastWritten.get()))
            .flatMap(row -> gridReadingRepository.save(row)
                .doOnNext(written -> lastWritten.set(row))
                .onErrorResume(failure -> Mono.fromRunnable(() ->
                    log.warn("The snapshot of the grid meter could not be written: {}", failure.toString()))))
            .then());
    }

    //the row of a state, or nothing while no readings came
    static Optional<GridReadingEntity> rowOf(final GridMeterState state) {
        return Optional.ofNullable(state.readings()).map(readings -> {
            final EmDataStatus counters = state.counters();
            return new GridReadingEntity(null, state.readingsAt(),
                readings.getaActPower(), readings.getbActPower(), readings.getcActPower(), readings.getTotalActPower(),
                counters == null ? null : kwh(counters.getTotalAct()),
                counters == null ? null : kwh(counters.getTotalActRet()),
                readings.getaVoltage(), readings.getbVoltage(), readings.getcVoltage());
        });
    }

    //a decimal shift, not a division: 891057.42 Wh / 1000 is 891.0574200000001 in binary floating point
    static Double kwh(final Double wh) {
        return wh == null ? null : BigDecimal.valueOf(wh).movePointLeft(WH_PER_KWH_DIGITS).doubleValue();
    }

    //the values, not the time: a reading repeated unchanged writes no row
    private static boolean sameValues(final GridReadingEntity row, final GridReadingEntity last) {
        return last != null
            && Objects.equals(row.aPower(), last.aPower()) && Objects.equals(row.bPower(), last.bPower())
            && Objects.equals(row.cPower(), last.cPower()) && Objects.equals(row.totalPower(), last.totalPower())
            && Objects.equals(row.importEnergy(), last.importEnergy())
            && Objects.equals(row.exportEnergy(), last.exportEnergy())
            && Objects.equals(row.aVoltage(), last.aVoltage()) && Objects.equals(row.bVoltage(), last.bVoltage())
            && Objects.equals(row.cVoltage(), last.cVoltage());
    }
}

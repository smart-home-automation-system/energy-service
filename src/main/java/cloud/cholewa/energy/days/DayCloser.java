package cloud.cholewa.energy.days;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.database.EnergyDayEntity;
import cloud.cholewa.energy.database.EnergyDayRepository;
import cloud.cholewa.energy.history.StoredValue;
import cloud.cholewa.energy.history.StoredValues;
import cloud.cholewa.energy.solaxcloud.CloudDays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

//Closes the days of the house into energy_day (HAS-331): every hour (and two minutes after the start) every day that is
//over and not closed yet - from the day after the last closed one, or from the first day the grid meter was stored on, at
//most MAX_DAYS_BACK days ago - so a day is closed within the hour after midnight of the house, and the days a downtime
//missed after it. A day is closed once and never changed.
//What a day is, from the stored readings (house time, [00:00, 24:00)):
//- the production of each inverter: the highest value of its daily counter in the day after COUNTER_SETTLES (daily_yield
//  of inverter_reading). The counter is the inverter's, reset by its own clock: the first minutes of a day may still
//  carry the day before, and one reset a little before midnight of the house would make the last value of the day 0 -
//  the highest is the total either way;
//- imported, exported: the counters of the grid meter at the end of the day minus at its start (the last values before
//  each midnight) - not known without a reading of the meter that day or before it;
//- charged, discharged: the daily counters of the SOLAX battery the same way (battery_reading, V8);
//- the cross-check of the SolaX cloud (CloudDays) when it was read that day.
//A database that fails decides nothing: the pass ends, the next one tries again. Bounded at LIMIT
@Slf4j
@Component
@RequiredArgsConstructor
public class DayCloser {

    static final Duration LIMIT = Duration.ofMinutes(5);
    static final int MAX_DAYS_BACK = 31;
    //the first minutes of a day, when a daily counter of an inverter may still carry the day before
    static final Duration COUNTER_SETTLES = Duration.ofMinutes(30);
    //how long after its midnight a day is closed at the earliest: the last SOFAR reading of a day reaches inverter_reading
    //up to five minutes after it was collected (the read of the Solarman cloud) and a minute more (the snapshot), the
    //other rows a minute after their readings (the /code-review of HAS-331)
    static final Duration CLOSE_AFTER = Duration.ofMinutes(15);

    private final EnergyDayRepository energyDayRepository;
    private final StoredValues storedValues;
    private final CloudDays cloudDays;
    private final Clock clock;

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1H")
    Mono<Void> close() {
        return ScheduledPass.bounded("day closing", LIMIT, () -> Mono.defer(this::closeOnce));
    }

    private Mono<Void> closeOnce() {
        //the first day that may not be closed yet: today, and before CLOSE_AFTER past midnight yesterday too
        final LocalDate open = LocalDateTime.now(clock).minus(CLOSE_AFTER).toLocalDate();
        return firstOpenDay(open)
            .flatMapMany(first -> Flux.fromStream(first.datesUntil(open)))
            .concatMap(this::closeDay)
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The days of the house could not be closed, tried again with the next pass: {}", failure.toString())));
    }

    //the day after the last closed one; without one the day of the first reading of the grid meter - never earlier than
    //MAX_DAYS_BACK days ago; empty while nothing was stored
    private Mono<LocalDate> firstOpenDay(final LocalDate today) {
        final LocalDate earliest = today.minusDays(MAX_DAYS_BACK);
        return energyDayRepository.findLastDay()
            .map(last -> last.plusDays(1))
            .switchIfEmpty(Mono.defer(() -> storedValues.firstTime(StoredValue.GRID_IMPORT)
                .map(LocalDateTime::toLocalDate)))
            .map(first -> first.isBefore(earliest) ? earliest : first);
    }

    private Mono<EnergyDayEntity> closeDay(final LocalDate day) {
        final LocalDateTime start = day.atStartOfDay();
        final LocalDateTime end = day.plusDays(1).atStartOfDay();
        //one query after the other: the pool has two connections, and the snapshots of the minute need one
        return Flux.concat(
                dailyCounter(StoredValue.YIELD_SOLAX, start, end),
                dailyCounter(StoredValue.YIELD_SOFAR, start, end),
                counter(StoredValue.GRID_IMPORT, start, end),
                counter(StoredValue.GRID_EXPORT, start, end),
                dailyCounter(StoredValue.BATTERY_CHARGED_TODAY, start, end),
                dailyCounter(StoredValue.BATTERY_DISCHARGED_TODAY, start, end))
            .collectList()
            .map(read -> row(day, read.stream().map(value -> value.orElse(null)).toList()))
            .flatMap(energyDayRepository::save)
            .doOnNext(row -> log.info("Day {} closed: production {} kWh, imported {} kWh, exported {} kWh, house load {} "
                + "kWh", row.day(), row.production(), row.imported(), row.exported(), row.houseLoad()));
    }

    //the six values in the order closeDay reads them, null where not known
    private EnergyDayEntity row(final LocalDate day, final List<BigDecimal> read) {
        final BigDecimal production = DayTotals.sum(read.get(0), read.get(1));
        final DayTotals totals = new DayTotals(production, read.get(2), read.get(3), read.get(4), read.get(5));
        final Optional<CloudDays.CloudDay> cloud = cloudDays.of(day);
        return new EnergyDayEntity(day, number(read.get(0)), number(read.get(1)), number(production),
            number(read.get(2)), number(read.get(3)), number(read.get(4)), number(read.get(5)), number(totals.houseLoad()),
            number(totals.selfConsumption()), number(totals.gridShare()),
            cloud.map(CloudDays.CloudDay::yield).orElse(null),
            cloud.map(CloudDays.CloudDay::imported).orElse(null),
            cloud.map(CloudDays.CloudDay::exported).orElse(null),
            LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
    }

    //the energy of a counter of the grid meter in the day: its last value of the day minus its last value before the
    //day; not known without a reading in the day or before it
    private Mono<Optional<BigDecimal>> counter(final StoredValue counter, final LocalDateTime start,
                                               final LocalDateTime end) {
        return storedValues.lastIn(counter, start, end)
            .zipWhen(last -> storedValues.lastBefore(counter, start))
            .map(read -> Optional.of(read.getT1().subtract(read.getT2()).setScale(2, RoundingMode.HALF_UP)))
            .defaultIfEmpty(Optional.empty());
    }

    //the total of a day of a daily counter: its highest value after COUNTER_SETTLES, kWh to 2 decimals; not known without
    //a reading then
    private Mono<Optional<BigDecimal>> dailyCounter(final StoredValue counter, final LocalDateTime start,
                                                    final LocalDateTime end) {
        return storedValues.maxIn(counter, start.plus(COUNTER_SETTLES), end)
            .map(value -> Optional.of(value.setScale(2, RoundingMode.HALF_UP)))
            .defaultIfEmpty(Optional.empty());
    }

    private static Double number(final BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}

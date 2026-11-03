package cloud.cholewa.energy.summary;

import cloud.cholewa.energy.api.model.BatteryReply;
import cloud.cholewa.energy.api.model.FlowsReply;
import cloud.cholewa.energy.api.model.HistoryReply;
import cloud.cholewa.energy.api.model.InverterReply;
import cloud.cholewa.energy.database.EnergyDayEntity;
import cloud.cholewa.energy.database.EnergyDayRepository;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
import cloud.cholewa.energy.days.SettlementProperties;
import cloud.cholewa.energy.flows.FlowsService;
import cloud.cholewa.energy.history.HistoryService;
import cloud.cholewa.energy.inverter.BatteryService;
import cloud.cholewa.energy.inverter.InverterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

//The numbers of the daily energy summary (HAS-339) at a moment of the house:
//- the day so far: "today" of GET /flows (the daily counters of both inverters, the grid meter since midnight, the
//  daily counters of the battery) and the daily counter of each inverter as GET /inverters answers it - a stale one is
//  not known; the SOC now as GET /battery answers it;
//- the hour of the highest production: the buckets of pv-power since midnight averaged per hour of the house; none
//  without a point above 0, or when the history cannot be read (a WARN - the rest of the summary stands);
//- the silence of a source: its silent episodes of source_alert (the SOLAX dongle, the grid meter - HAS-332) cover more
//  than half of the day so far;
//- the month: the closed days of energy_day from its first day to yesterday, and today when its grid is known.
//One query after the other - the pool has two connections. A database that fails fails the summary (the watch tries
//again)
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryService {

    static final String SILENT = "silent";
    private static final String SOLAX = "solax";
    private static final String SOFAR = "sofar";
    private static final String METER = "meter";
    private static final String PV_POWER = "pv-power";

    private final FlowsService flowsService;
    private final InverterService inverterService;
    private final BatteryService batteryService;
    private final HistoryService historyService;
    private final SourceAlertRepository sourceAlertRepository;
    private final EnergyDayRepository energyDayRepository;
    private final SettlementProperties settlementProperties;

    private record Day(FlowsReply.Today today, BigDecimal solax, BigDecimal sofar, BigDecimal soc) {
    }

    public Mono<DaySummary> summary(final LocalDateTime now) {
        final LocalDateTime midnight = now.toLocalDate().atStartOfDay();
        return day()
            .zipWhen(day -> peak(midnight, now))
            .zipWhen(read -> silent(midnight, now))
            .zipWhen(read -> closedDays(now.toLocalDate()))
            .map(read -> {
                final Day day = read.getT1().getT1().getT1();
                final Optional<DaySummary.Peak> peak = read.getT1().getT1().getT2();
                final Collection<String> silent = read.getT1().getT2();
                return summary(now, day, peak.orElse(null), silent, read.getT2());
            });
    }

    private Mono<Day> day() {
        return flowsService.flows()
            .zipWhen(flows -> dailyYield(SOLAX))
            .zipWhen(read -> dailyYield(SOFAR))
            .zipWhen(read -> batteryService.battery())
            .map(read -> new Day(
                Optional.ofNullable(read.getT1().getT1().getT1().today()).orElseGet(SummaryService::nothingToday),
                read.getT1().getT1().getT2().orElse(null),
                read.getT1().getT2().orElse(null),
                soc(read.getT2())));
    }

    private static FlowsReply.Today nothingToday() {
        return new FlowsReply.Today(null, null, null, null, null, null, null, null);
    }

    //the daily counter of an inverter while it is not stale; an inverter not registered, or a registry not known, is
    //not known
    private Mono<Optional<BigDecimal>> dailyYield(final String id) {
        return inverterService.inverter(id)
            .map(reply -> Optional.of(reply).filter(fresh -> !fresh.stale()).map(InverterReply::dailyYield)
                .map(BigDecimal::valueOf))
            .onErrorResume(failure -> Mono.just(Optional.empty()))
            .defaultIfEmpty(Optional.empty());
    }

    private static BigDecimal soc(final BatteryReply battery) {
        return Optional.of(battery).filter(fresh -> !fresh.stale()).map(BatteryReply::soc).map(BigDecimal::valueOf)
            .orElse(null);
    }

    private Mono<Optional<DaySummary.Peak>> peak(final LocalDateTime midnight, final LocalDateTime now) {
        return historyService.history(PV_POWER, midnight, now)
            .map(history -> peakOf(history.points()))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The production of the day could not be read for the daily summary: {}", failure.toString()))
                .thenReturn(Optional.empty()));
    }

    //the hour with the highest average of its buckets; none without a point above 0
    static Optional<DaySummary.Peak> peakOf(final List<HistoryReply.Point> points) {
        final Map<LocalTime, List<BigDecimal>> byHour = points.stream()
            .filter(point -> point.avg() != null)
            .collect(Collectors.groupingBy(point -> point.at().toLocalTime().truncatedTo(ChronoUnit.HOURS),
                Collectors.mapping(HistoryReply.Point::avg, Collectors.toList())));
        return byHour.entrySet().stream()
            .map(hour -> new DaySummary.Peak(hour.getKey(), average(hour.getValue())))
            .filter(peak -> peak.averageWatts().signum() > 0)
            .max(Comparator.comparing(DaySummary.Peak::averageWatts));
    }

    private static BigDecimal average(final List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    //the sources whose silent episodes cover more than half of [midnight, now)
    private Mono<Collection<String>> silent(final LocalDateTime midnight, final LocalDateTime now) {
        return sourceAlertRepository.findOverlapping(SILENT, midnight, now)
            .collectList()
            .map(episodes -> silentOf(episodes, midnight, now));
    }

    static Collection<String> silentOf(final List<SourceAlertEntity> episodes, final LocalDateTime midnight,
                                       final LocalDateTime now) {
        final Duration half = between(midnight, now).dividedBy(2);
        return episodes.stream()
            .collect(Collectors.groupingBy(SourceAlertEntity::source,
                Collectors.reducing(Duration.ZERO, episode -> covered(episode, midnight, now), Duration::plus)))
            .entrySet().stream()
            .filter(source -> source.getValue().compareTo(half) > 0)
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    private static Duration covered(final SourceAlertEntity episode, final LocalDateTime midnight,
                                    final LocalDateTime now) {
        final LocalDateTime start = episode.openedAt().isBefore(midnight) ? midnight : episode.openedAt();
        final LocalDateTime end = Optional.ofNullable(episode.closedAt()).filter(closed -> closed.isBefore(now))
            .orElse(now);
        return end.isAfter(start) ? between(start, end) : Duration.ZERO;
    }

    //in the house zone: a day of a change of the clock is 23 or 25 hours long
    private static Duration between(final LocalDateTime from, final LocalDateTime to) {
        return Duration.between(from.atZone(ZoneId.systemDefault()), to.atZone(ZoneId.systemDefault()));
    }

    private Mono<List<EnergyDayEntity>> closedDays(final LocalDate today) {
        return Mono.just(today)
            .filter(day -> day.getDayOfMonth() > 1)
            .flatMap(day -> energyDayRepository.findByDayBetweenOrderByDay(day.withDayOfMonth(1), day.minusDays(1))
                .collectList())
            .defaultIfEmpty(List.of());
    }

    private DaySummary summary(final LocalDateTime now, final Day day, final DaySummary.Peak peak,
                               final Collection<String> silent, final List<EnergyDayEntity> closed) {
        final FlowsReply.Today today = day.today();
        final boolean meterSilent = silent.contains(METER);
        return new DaySummary(now, day.solax(), day.sofar(), today.production(), peak, today.houseLoad(),
            today.selfConsumption(), today.imported(), today.exported(), today.charged(), today.discharged(),
            day.soc(), month(now.toLocalDate(), today, closed, meterSilent), silent.contains(SOLAX), meterSilent);
    }

    //the month of the closed days with today when its grid is known and the meter was not silent for most of it
    private DaySummary.Month month(final LocalDate day, final FlowsReply.Today today,
                                   final List<EnergyDayEntity> closed, final boolean meterSilent) {
        final boolean withToday = !meterSilent && today.imported() != null && today.exported() != null;
        final BigDecimal imported = sum(closed, EnergyDayEntity::imported)
            .add(withToday ? today.imported() : BigDecimal.ZERO);
        final BigDecimal exported = sum(closed, EnergyDayEntity::exported)
            .add(withToday ? today.exported() : BigDecimal.ZERO);
        return closed.isEmpty() && !withToday ? null
            : new DaySummary.Month(YearMonth.from(day), imported, exported, settlementProperties.ratio(), withToday);
    }

    private static BigDecimal sum(final List<EnergyDayEntity> days, final Function<EnergyDayEntity, Double> value) {
        return days.stream().map(value).filter(Objects::nonNull).map(BigDecimal::valueOf)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

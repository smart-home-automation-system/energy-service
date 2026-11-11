package cloud.cholewa.energy.days;

import cloud.cholewa.energy.api.model.DayReply;
import cloud.cholewa.energy.api.model.MonthsReply;
import cloud.cholewa.energy.database.ElicznikRepository;
import cloud.cholewa.energy.database.EnergyDayEntity;
import cloud.cholewa.energy.database.EnergyDayRepository;
import cloud.cholewa.energy.error.EnergyException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

//GET /home/energy/days and /months (HAS-331): the closed days of energy_day and the billing year with its net-metering
//(Settlement). The months read every closed day up to the end of the year asked - the credit of a month depends on every
//month before it; energy_day holds a row a day, a few hundred a year
@Service
@RequiredArgsConstructor
public class DaysService {

    static final long MAX_DAYS = 366;
    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 9999;

    private final EnergyDayRepository energyDayRepository;
    private final ElicznikRepository elicznikRepository;
    private final SettlementProperties settlementProperties;

    //the closed days of [from, to], both included, at most MAX_DAYS
    public Mono<List<DayReply>> days(final LocalDate from, final LocalDate to) {
        return Mono.justOrEmpty(daysViolation(from, to))
            .flatMap(Mono::<List<DayReply>>error)
            .switchIfEmpty(Mono.defer(() -> energyDayRepository.findByDayBetweenOrderByDay(from, to)
                .collectList()
                .zipWhen(days -> elicznikRepository.findDays(from, to)
                    .collectMap(ElicznikRepository.DayEnergy::day, Function.identity()))
                .map(both -> both.getT1().stream()
                    .map(day -> reply(day, both.getT2().get(day.day())))
                    .toList())));
    }

    //the billing year that starts in the month energy.settlement.period-start of "year"
    public Mono<MonthsReply> months(final int year) {
        return Mono.just(year)
            .filter(asked -> asked >= MIN_YEAR && asked < MAX_YEAR)
            .switchIfEmpty(Mono.error(() -> EnergyException.invalidRange(
                "year must be within " + MIN_YEAR + " to " + (MAX_YEAR - 1))))
            .flatMap(asked -> {
                final YearMonth first = YearMonth.of(asked, settlementProperties.periodStart());
                final YearMonth last = first.plusMonths(11);
                return energyDayRepository.findByDayLessThanEqualOrderByDay(last.atEndOfMonth())
                    .collectList()
                    .zipWhen(days -> elicznikRepository.findDays(LocalDate.of(MIN_YEAR, Month.JANUARY, 1), last.atEndOfMonth())
                        .collectList())
                    .map(both -> reply(asked, first, last, both.getT1(), both.getT2()));
            });
    }

    private MonthsReply reply(final int year, final YearMonth first, final YearMonth last,
                              final List<EnergyDayEntity> days, final List<ElicznikRepository.DayEnergy> operator) {
        final Map<YearMonth, List<EnergyDayEntity>> byMonth = days.stream()
            .collect(Collectors.groupingBy(day -> YearMonth.from(day.day())));
        final YearMonth start = days.stream().findFirst().map(day -> YearMonth.from(day.day()))
            .filter(firstDay -> firstDay.isBefore(first)).orElse(first);
        final List<Settlement.MonthEnergy> energies = new ArrayList<>();
        for (YearMonth month = start; !month.isAfter(last); month = month.plusMonths(1)) {
            final List<EnergyDayEntity> ofMonth = byMonth.getOrDefault(month, List.of());
            energies.add(new Settlement.MonthEnergy(month, total(ofMonth, EnergyDayEntity::imported),
                total(ofMonth, EnergyDayEntity::exported)));
        }
        final Map<YearMonth, Settlement.MonthSettlement> settled = Settlement.settle(energies,
                settlementProperties.ratio()).stream()
            .collect(Collectors.toMap(Settlement.MonthSettlement::month, Function.identity()));
        final Map<YearMonth, List<ElicznikRepository.DayEnergy>> operatorByMonth = operator.stream()
            .collect(Collectors.groupingBy(day -> YearMonth.from(day.day())));
        final Map<YearMonth, Settlement.MonthSettlement> operatorSettled = operatorSettlement(operator, operatorByMonth,
            last);
        final List<MonthsReply.Month> months = new ArrayList<>();
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            final List<EnergyDayEntity> ofMonth = byMonth.getOrDefault(month, List.of());
            final Settlement.MonthSettlement settlement = settled.get(month);
            final List<ElicznikRepository.DayEnergy> ofOperator = operatorByMonth.getOrDefault(month, List.of());
            final Optional<Settlement.MonthSettlement> byOperator = Optional.ofNullable(operatorSettled.get(month))
                .filter(settledMonth -> !ofOperator.isEmpty());
            months.add(new MonthsReply.Month(month, ofMonth.size(),
                sumOrNull(ofMonth, EnergyDayEntity::production), sumOrNull(ofMonth, EnergyDayEntity::imported),
                sumOrNull(ofMonth, EnergyDayEntity::exported), sumOrNull(ofMonth, EnergyDayEntity::houseLoad),
                settlement.credited(), settlement.drawn(), settlement.bought(), settlement.expired(),
                settlement.stored(),
                byOperator.map(settledMonth -> operatorTotal(ofOperator, ElicznikRepository.DayEnergy::imported))
                    .orElse(null),
                byOperator.map(settledMonth -> operatorTotal(ofOperator, ElicznikRepository.DayEnergy::exported))
                    .orElse(null),
                byOperator.map(Settlement.MonthSettlement::credited).orElse(null),
                byOperator.map(Settlement.MonthSettlement::stored).orElse(null)));
        }
        return new MonthsReply(year, first, last, settlementProperties.ratio(), months);
    }

    //the side of the operator (HAS-340): the months from the first one the portal served a day of, without gaps, to "last"
    private Map<YearMonth, Settlement.MonthSettlement> operatorSettlement(
        final List<ElicznikRepository.DayEnergy> operator,
        final Map<YearMonth, List<ElicznikRepository.DayEnergy>> byMonth, final YearMonth last) {
        if (operator.isEmpty()) {
            return Map.of();
        }
        final List<Settlement.MonthEnergy> energies = new ArrayList<>();
        for (YearMonth month = YearMonth.from(operator.getFirst().day()); !month.isAfter(last); month = month.plusMonths(1)) {
            final List<ElicznikRepository.DayEnergy> ofMonth = byMonth.getOrDefault(month, List.of());
            energies.add(new Settlement.MonthEnergy(month,
                operatorTotal(ofMonth, ElicznikRepository.DayEnergy::imported),
                operatorTotal(ofMonth, ElicznikRepository.DayEnergy::exported)));
        }
        return Settlement.settle(energies, settlementProperties.ratio()).stream()
            .collect(Collectors.toMap(Settlement.MonthSettlement::month, Function.identity()));
    }

    private static BigDecimal operatorTotal(final List<ElicznikRepository.DayEnergy> days,
                                            final Function<ElicznikRepository.DayEnergy, BigDecimal> value) {
        return days.stream().map(value).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
    }

    //the sum of the known values of the days, 0 without one - what the settlement counts
    private static BigDecimal total(final List<EnergyDayEntity> days, final Function<EnergyDayEntity, Double> value) {
        return Optional.ofNullable(sumOrNull(days, value)).orElse(BigDecimal.ZERO);
    }

    //the sum of the known values of the days, null when no day knows it
    private static BigDecimal sumOrNull(final List<EnergyDayEntity> days, final Function<EnergyDayEntity, Double> value) {
        final List<BigDecimal> known = days.stream().map(value).filter(Objects::nonNull).map(BigDecimal::valueOf).toList();
        return known.isEmpty() ? null
            : known.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private static Optional<EnergyException> daysViolation(final LocalDate from, final LocalDate to) {
        if (from.getYear() < MIN_YEAR || to.getYear() >= MAX_YEAR) {
            return Optional.of(EnergyException.invalidRange("from and to must be within the years " + MIN_YEAR + " to "
                + (MAX_YEAR - 1)));
        }
        if (to.isBefore(from)) {
            return Optional.of(EnergyException.invalidRange("from must not be after to"));
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            return Optional.of(EnergyException.invalidRange("The range must not be longer than " + MAX_DAYS + " days"));
        }
        return Optional.empty();
    }

    private static DayReply reply(final EnergyDayEntity day, final ElicznikRepository.DayEnergy operator) {
        final Optional<ElicznikRepository.DayEnergy> byOperator = Optional.ofNullable(operator);
        return new DayReply(day.day(), day.solaxYield(), day.sofarYield(), day.production(), day.imported(),
            day.exported(), day.charged(), day.discharged(), day.houseLoad(), day.selfConsumption(), day.gridShare(),
            day.cloudYield(), day.cloudImported(), day.cloudExported(),
            byOperator.map(ElicznikRepository.DayEnergy::imported).orElse(null),
            byOperator.map(ElicznikRepository.DayEnergy::exported).orElse(null));
    }
}

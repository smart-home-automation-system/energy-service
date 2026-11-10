package cloud.cholewa.energy.elicznik;

import cloud.cholewa.energy.api.model.ElicznikReply;
import cloud.cholewa.energy.database.ElicznikHourEntity;
import cloud.cholewa.energy.database.ElicznikMeterEntity;
import cloud.cholewa.energy.database.ElicznikRepository;
import cloud.cholewa.energy.error.EnergyException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

//GET /home/energy/elicznik (HAS-340): the stored days of the portal. Two queries one after the other (the pool of 2)
@Service
@RequiredArgsConstructor
public class ElicznikService {

    static final long MAX_DAYS = 31;
    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 9999;

    private final ElicznikRepository repository;

    //the stored days of [from, to], both included, at most MAX_DAYS
    public Mono<ElicznikReply> days(final LocalDate from, final LocalDate to) {
        return Mono.justOrEmpty(violation(from, to))
            .flatMap(Mono::<ElicznikReply>error)
            .switchIfEmpty(Mono.defer(() -> repository.findHours(from, to)
                .collectList()
                .zipWhen(hours -> repository.findMeters(from, to)
                    .collectMap(ElicznikMeterEntity::day, Function.identity()))
                .map(both -> new ElicznikReply(from, to, days(both.getT1(), both.getT2())))));
    }

    private static List<ElicznikReply.Day> days(final List<ElicznikHourEntity> hours,
                                                final Map<LocalDate, ElicznikMeterEntity> meters) {
        final Map<LocalDate, List<ElicznikHourEntity>> byDay = hours.stream()
            .collect(Collectors.groupingBy(ElicznikHourEntity::day, LinkedHashMap::new, Collectors.toList()));
        return byDay.entrySet().stream()
            .map(day -> {
                final Optional<ElicznikMeterEntity> meter = Optional.ofNullable(meters.get(day.getKey()));
                return new ElicznikReply.Day(day.getKey(),
                    sum(day.getValue(), ElicznikHourEntity::imported), sum(day.getValue(), ElicznikHourEntity::exported),
                    meter.map(ElicznikMeterEntity::imported).orElse(null),
                    meter.map(ElicznikMeterEntity::exported).orElse(null),
                    day.getValue().stream()
                        .map(hour -> new ElicznikReply.Hour(hour.hour(), hour.repeated(), hour.imported(), hour.exported()))
                        .toList());
            })
            .toList();
    }

    private static BigDecimal sum(final List<ElicznikHourEntity> hours, final Function<ElicznikHourEntity, BigDecimal> of) {
        return hours.stream().map(of).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Optional<EnergyException> violation(final LocalDate from, final LocalDate to) {
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
}

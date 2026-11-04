package cloud.cholewa.energy.forecast;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.database.EnergyDayEntity;
import cloud.cholewa.energy.database.EnergyDayRepository;
import cloud.cholewa.energy.database.PvForecastCalibrationEntity;
import cloud.cholewa.energy.database.PvForecastCalibrationRepository;
import cloud.cholewa.energy.database.PvForecastDayEntity;
import cloud.cholewa.energy.database.PvForecastDayRepository;
import cloud.cholewa.energy.database.PvForecastEntity;
import cloud.cholewa.energy.database.PvForecastRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

//Calibrates the forecast on the closed days (HAS-372): every hour (five minutes after the start), as DayCloser closes the
//days - a day closed a little after midnight is calibrated within the hour after it, a pass that failed is caught up by
//the next, and an hour more costs two reads. One pass:
//1. every closed day of energy_day in the last energy.forecast.calibration-days, not yet in pv_forecast_day, whose
//   inverter has a production that day and a forecast with an irradiance for every hour of the day (house times: 23 on
//   the day summer time starts; the doubled 02:00 of its end is one row, so 24), becomes a row of pv_forecast_day: the forecast at a ratio of 1.0 and the production;
//2. the ratio of each inverter = the sum of its productions / the sum of its forecasts over the rows of the window,
//   clamped MIN_RATIO - MAX_RATIO; fewer than min-calibration-days rows (or no forecast energy) keep the initial ratio,
//   stored with 0 days. pv_forecast_calibration holds it, so a restart keeps it; GET /forecast reads it there.
//Snow on the panels is noise the window averages out (owner, HAS-372). The forecast of a day is what was fetched before
//each of its hours started (pv_forecast keeps a started hour as it was). One query after the other (the pool of 2);
//bounded at LIMIT; a failure is a WARN, the next pass tries again
@Slf4j
@Component
@RequiredArgsConstructor
public class ForecastCalibration {

    static final Duration LIMIT = Duration.ofMinutes(5);
    static final double MIN_RATIO = 0.3;
    static final double MAX_RATIO = 1.0;

    private final EnergyDayRepository energyDayRepository;
    private final PvForecastRepository pvForecastRepository;
    private final PvForecastDayRepository pvForecastDayRepository;
    private final PvForecastCalibrationRepository calibrationRepository;
    private final ForecastProperties forecastProperties;
    private final OutlookProperties outlookProperties;
    private final Clock clock;

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
    Mono<Void> calibrate() {
        return ScheduledPass.bounded("forecast calibration", LIMIT, () -> Mono.defer(this::calibrateOnce));
    }

    private Mono<Void> calibrateOnce() {
        final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        final LocalDate last = now.toLocalDate().minusDays(1);
        final LocalDate first = now.toLocalDate().minusDays(outlookProperties.calibrationDays());
        return pvForecastDayRepository.findBetween(first, last).collectList()
            .flatMap(stored -> energyDayRepository.findByDayBetweenOrderByDay(first, last)
                .concatMap(day -> newRows(day, stored, now))
                .collectList()
                .map(added -> {
                    final List<PvForecastDayEntity> rows = new ArrayList<>(stored);
                    rows.addAll(added);
                    return rows;
                }))
            .flatMapMany(rows -> Flux.fromIterable(forecastProperties.planes().keySet())
                .concatMap(inverter -> store(calibration(inverter, rows, now))))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The forecast could not be calibrated, tried again with the next pass: {}", failure.toString())));
    }

    //the rows of the inverters of the day not stored yet; the forecast of the day read only when one is missing
    private Flux<PvForecastDayEntity> newRows(final EnergyDayEntity day, final List<PvForecastDayEntity> stored,
                                              final LocalDateTime now) {
        final Map<String, Double> actual = actual(day);
        final List<String> missing = forecastProperties.planes().keySet().stream()
            .filter(inverter -> actual.get(inverter) != null)
            .filter(inverter -> stored.stream().noneMatch(row -> row.day().equals(day.day())
                && row.inverter().equals(inverter)))
            .toList();
        final LocalDateTime start = day.day().atStartOfDay();
        final LocalDateTime end = day.day().plusDays(1).atStartOfDay();
        final long hours = localHours(day.day());
        return Mono.just(missing)
            .filter(inverters -> !inverters.isEmpty())
            .flatMap(inverters -> pvForecastRepository.findBetween(start, end).collectList())
            .flatMapIterable(forecast -> missing.stream()
                .map(inverter -> row(day.day(), inverter, forecast, hours, actual.get(inverter), now))
                .filter(Objects::nonNull)
                .toList())
            .concatMap(row -> pvForecastDayRepository.save(row)
                .filter(saved -> saved > 0)
                .map(saved -> row));
    }

    //the hours of the day as pv_forecast stores them, house times: 24, 23 on the day summer time starts (its 02:00 does
    //not exist), 24 on the day it ends too - its 02:00 happens twice but is one time of the house, one row
    private long localHours(final LocalDate day) {
        return Stream.iterate(day.atStartOfDay(), hour -> hour.toLocalDate().equals(day), hour -> hour.plusHours(1))
            .filter(hour -> !clock.getZone().getRules().getValidOffsets(hour).isEmpty())
            .count();
    }

    private static Map<String, Double> actual(final EnergyDayEntity day) {
        final Map<String, Double> actual = new HashMap<>();
        actual.put("solax", day.solaxYield());
        actual.put("sofar", day.sofarYield());
        return actual;
    }

    //null without an irradiance for every hour of the day
    private PvForecastDayEntity row(final LocalDate day, final String inverter, final List<PvForecastEntity> forecast,
                                    final long hours, final double actual, final LocalDateTime now) {
        final List<Double> irradiances = forecast.stream()
            .filter(hour -> hour.inverter().equals(inverter))
            .map(PvForecastEntity::irradiance)
            .filter(Objects::nonNull)
            .toList();
        if (irradiances.size() != hours) {
            return null;
        }
        final double peakKwp = forecastProperties.planes().get(inverter).peakKwp();
        return new PvForecastDayEntity(day, inverter, ForecastModel.kwh(peakKwp, irradiances, 1.0), actual, now);
    }

    //the ratio of the inverter over the rows of the window
    PvForecastCalibrationEntity calibration(final String inverter, final List<PvForecastDayEntity> rows,
                                            final LocalDateTime now) {
        final List<PvForecastDayEntity> own = rows.stream().filter(row -> row.inverter().equals(inverter)).toList();
        final double forecast = own.stream().mapToDouble(PvForecastDayEntity::forecastKwh).sum();
        final double actual = own.stream().mapToDouble(PvForecastDayEntity::actualKwh).sum();
        if (own.size() < outlookProperties.minCalibrationDays() || forecast <= 0) {
            return new PvForecastCalibrationEntity(inverter, outlookProperties.initialPerformanceRatio(), 0, now);
        }
        final double ratio = Math.clamp(actual / forecast, MIN_RATIO, MAX_RATIO);
        return new PvForecastCalibrationEntity(inverter, ratio, own.size(), now);
    }

    private Mono<Long> store(final PvForecastCalibrationEntity calibration) {
        return calibrationRepository.save(calibration)
            .filter(changed -> changed > 0)
            .doOnNext(changed -> log.info("The forecast of the {} calibrated: performance ratio {} on {} days",
                calibration.inverter().toUpperCase(Locale.ROOT), String.format(Locale.ROOT, "%.3f", calibration.ratio()),
                calibration.days()));
    }

}

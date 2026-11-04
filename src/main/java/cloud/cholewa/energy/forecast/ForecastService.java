package cloud.cholewa.energy.forecast;

import cloud.cholewa.energy.api.model.ForecastReply;
import cloud.cholewa.energy.database.EnergyDayEntity;
import cloud.cholewa.energy.database.EnergyDayRepository;
import cloud.cholewa.energy.database.PvForecastCalibrationEntity;
import cloud.cholewa.energy.database.PvForecastCalibrationRepository;
import cloud.cholewa.energy.database.PvForecastDayEntity;
import cloud.cholewa.energy.database.PvForecastDayRepository;
import cloud.cholewa.energy.database.PvForecastEntity;
import cloud.cholewa.energy.database.PvForecastRepository;
import cloud.cholewa.energy.time.HouseTimes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

//GET /home/energy/forecast (HAS-372): the stored forecast (pv_forecast) turned into kWh by ForecastModel at the ratio
//of pv_forecast_calibration (the initial one before a calibration), judged against the house load by ForecastRules. Four
//queries, one after the other (the pool of 2): the ratios, the forecast of the seven days, the last seven closed days,
//the last two calibrated days
@Service
@RequiredArgsConstructor
public class ForecastService {

    static final int DAYS = 7;
    static final int HOURLY_HOURS = 48;
    static final int PAST_DAYS = 2;
    //the irradiance on the ground above which an hour counts as sunshine - the threshold of the WMO for direct sunlight
    static final double SUNSHINE_IRRADIANCE = 120;
    private static final String SOLAX = "solax";
    private static final String SOFAR = "sofar";

    private final PvForecastRepository pvForecastRepository;
    private final PvForecastDayRepository pvForecastDayRepository;
    private final PvForecastCalibrationRepository calibrationRepository;
    private final EnergyDayRepository energyDayRepository;
    private final ForecastState forecastState;
    private final ForecastProperties forecastProperties;
    private final OutlookProperties outlookProperties;
    private final Clock clock;

    public Mono<ForecastReply> forecast() {
        return Mono.defer(() -> {
            final LocalDateTime now = LocalDateTime.now(clock);
            final LocalDate today = now.toLocalDate();
            return calibrationRepository.findAll().collectList()
                .flatMap(ratios -> pvForecastRepository.findBetween(today.atStartOfDay(),
                        today.plusDays(DAYS).atStartOfDay()).collectList()
                    .flatMap(hours -> energyDayRepository.findTop7ByDayLessThanOrderByDayDesc(today).collectList()
                        .flatMap(closed -> pvForecastDayRepository.findBetween(today.minusDays(PAST_DAYS),
                                today.minusDays(1)).collectList()
                            .map(past -> reply(now, new Inputs(ratios(ratios), hours, closed, past))))));
        });
    }

    private record Inputs(
        Map<String, ForecastReply.Ratio> ratios,
        List<PvForecastEntity> hours,
        List<EnergyDayEntity> closed,
        List<PvForecastDayEntity> past
    ) {

        double ratio(final String inverter) {
            return ratios.get(inverter).ratio();
        }
    }

    //the stored ratio of each inverter, the initial one with 0 days without a row
    private Map<String, ForecastReply.Ratio> ratios(final List<PvForecastCalibrationEntity> stored) {
        final Map<String, PvForecastCalibrationEntity> byInverter = stored.stream()
            .collect(Collectors.toMap(PvForecastCalibrationEntity::inverter, Function.identity()));
        return forecastProperties.planes().keySet().stream().collect(Collectors.toMap(Function.identity(),
            inverter -> Optional.ofNullable(byInverter.get(inverter))
                .map(row -> new ForecastReply.Ratio(round(row.ratio(), 3), row.days()))
                .orElseGet(() -> new ForecastReply.Ratio(outlookProperties.initialPerformanceRatio(), 0))));
    }

    private ForecastReply reply(final LocalDateTime now, final Inputs inputs) {
        final ForecastRules.Reference reference = ForecastRules.reference(inputs.closed().stream()
            .map(EnergyDayEntity::houseLoad).toList());
        final List<ForecastReply.Day> days = now.toLocalDate().datesUntil(now.toLocalDate().plusDays(DAYS))
            .map(day -> day(day, inputs, reference))
            .toList();
        final ForecastRules.Outlook outlook = ForecastRules.outlook(days.stream().map(ForecastReply.Day::day).toList(),
            days.stream().map(ForecastReply.Day::category).toList());
        final LocalDateTime fetchedAt = fetchedAt();
        return new ForecastReply(
            fetchedAt,
            fetchedAt == null || HouseTimes.between(fetchedAt, now).compareTo(outlookProperties.staleAfter()) > 0,
            reference == null ? null : round(reference.kwh(), 2),
            reference == null ? null : reference.days(),
            new ForecastReply.Ratios(inputs.ratios().get(SOLAX), inputs.ratios().get(SOFAR)),
            days,
            past(inputs),
            hourly(now, inputs),
            outlook.sunBackOn() == null && outlook.daysWithoutSun() == null ? null
                : new ForecastReply.Outlook(outlook.sunBackOn(), outlook.daysWithoutSun()));
    }

    //the older of the last fetches of both planes; null while one of them was never fetched
    private LocalDateTime fetchedAt() {
        final List<Optional<LocalDateTime>> fetches = forecastProperties.planes().keySet().stream()
            .map(inverter -> forecastState.of(inverter).map(ForecastState.Plane::fetchedAt))
            .toList();
        return fetches.stream().anyMatch(Optional::isEmpty) ? null
            : fetches.stream().map(Optional::get).min(Comparator.naturalOrder())
                .map(fetch -> fetch.truncatedTo(ChronoUnit.SECONDS)).orElse(null);
    }

    private ForecastReply.Day day(final LocalDate day, final Inputs inputs, final ForecastRules.Reference reference) {
        final List<PvForecastEntity> hours = inputs.hours().stream()
            .filter(hour -> hour.hour().toLocalDate().equals(day))
            .toList();
        final Double solax = kwh(SOLAX, hours, inputs);
        final Double sofar = kwh(SOFAR, hours, inputs);
        final Double total = solax == null || sofar == null ? null : solax + sofar;
        final Double coverage = ForecastRules.coverage(total, reference);
        final List<PvForecastEntity> sky = sky(hours);
        return new ForecastReply.Day(
            day,
            round(solax, 2),
            round(sofar, 2),
            round(total, 2),
            round(coverage, 2),
            ForecastRules.category(coverage, outlookProperties.bands()),
            daytimeCloudCover(sky),
            sky.isEmpty() ? null : (int) sky.stream()
                .filter(hour -> hour.horizontalIrradiance() != null
                    && hour.horizontalIrradiance() > SUNSHINE_IRRADIANCE)
                .count());
    }

    private Double kwh(final String inverter, final List<PvForecastEntity> hours, final Inputs inputs) {
        return ForecastModel.kwh(forecastProperties.planes().get(inverter).peakKwp(), hours.stream()
            .filter(hour -> hour.inverter().equals(inverter))
            .map(PvForecastEntity::irradiance)
            .toList(), inputs.ratio(inverter));
    }

    //the sky of the hours - the same for both planes (one place): the hours of the SOLAX, those of the SOFAR without them
    private static List<PvForecastEntity> sky(final List<PvForecastEntity> hours) {
        final List<PvForecastEntity> solax = hours.stream().filter(hour -> hour.inverter().equals(SOLAX)).toList();
        return solax.isEmpty() ? hours.stream().filter(hour -> hour.inverter().equals(SOFAR)).toList() : solax;
    }

    //the mean cloud cover of the hours the sun is up (some irradiance on the ground), null without one
    private static Double daytimeCloudCover(final List<PvForecastEntity> sky) {
        return sky.stream()
            .filter(hour -> hour.horizontalIrradiance() != null && hour.horizontalIrradiance() > 0)
            .map(PvForecastEntity::cloudCover)
            .filter(Objects::nonNull)
            .mapToDouble(Double::doubleValue)
            .average()
            .stream()
            .boxed()
            .map(mean -> round(mean, 0))
            .findFirst()
            .orElse(null);
    }

    private List<ForecastReply.Past> past(final Inputs inputs) {
        final Map<LocalDate, List<PvForecastDayEntity>> byDay = inputs.past().stream()
            .collect(Collectors.groupingBy(PvForecastDayEntity::day, TreeMap::new, Collectors.toList()));
        return byDay.entrySet().stream()
            .map(day -> new ForecastReply.Past(day.getKey(), compared(SOLAX, day.getValue(), inputs),
                compared(SOFAR, day.getValue(), inputs)))
            .toList();
    }

    private static ForecastReply.Compared compared(final String inverter, final List<PvForecastDayEntity> rows,
                                                   final Inputs inputs) {
        return rows.stream()
            .filter(row -> row.inverter().equals(inverter))
            .findFirst()
            .map(row -> new ForecastReply.Compared(round(row.forecastKwh() * inputs.ratio(inverter), 2),
                round(row.actualKwh(), 2)))
            .orElse(null);
    }

    private List<ForecastReply.Hour> hourly(final LocalDateTime now, final Inputs inputs) {
        final LocalDateTime from = now.truncatedTo(ChronoUnit.HOURS);
        final LocalDateTime to = from.plusHours(HOURLY_HOURS);
        final Map<LocalDateTime, List<PvForecastEntity>> byHour = inputs.hours().stream()
            .filter(hour -> !hour.hour().isBefore(from) && hour.hour().isBefore(to))
            .collect(Collectors.groupingBy(PvForecastEntity::hour, TreeMap::new, Collectors.toList()));
        return byHour.entrySet().stream()
            .map(hour -> new ForecastReply.Hour(
                hour.getKey(),
                round(kwh(SOLAX, hour.getValue(), inputs), 2),
                round(kwh(SOFAR, hour.getValue(), inputs), 2),
                sky(hour.getValue()).stream().map(PvForecastEntity::cloudCover).filter(Objects::nonNull)
                    .findFirst().map(cloud -> round(cloud, 0)).orElse(null)))
            .toList();
    }

    private static Double round(final Double value, final int decimals) {
        if (value == null) {
            return null;
        }
        final double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }
}

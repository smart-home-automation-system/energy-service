package cloud.cholewa.energy.forecast;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.database.PvForecastEntity;
import cloud.cholewa.energy.database.PvForecastRepository;
import cloud.cholewa.energy.solax.FailureLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

//Fetches the irradiance forecast of both planes of panels (HAS-371): 10 s after the start and every
//energy.forecast.interval (PT1H; a bare number is minutes), bounded at LIMIT. One plane after the other (the pool of 2),
//each with its own onErrorResume, so a failure of one keeps the other. Only the hours not yet started are written, one row
//after the other - the forecast of an hour that started is kept as it was (the calibration of HAS-372 compares it).
//A failure is one WARN an hour a plane (FailureLog), an INFO on the return; ForecastState holds the last fetch of each.
//Nothing is published: a forecast is not a device of the house
@Slf4j
@Component
public class ForecastFetch {

    static final Duration LIMIT = Duration.ofMinutes(5);

    private final OpenMeteoClient client;
    private final PvForecastRepository repository;
    private final ForecastState state;
    private final ForecastProperties properties;
    private final Clock clock;
    private final Map<String, FailureLog> failureLogs;

    public ForecastFetch(
        final OpenMeteoClient client,
        final PvForecastRepository repository,
        final ForecastState state,
        final ForecastProperties properties,
        final Clock clock
    ) {
        this.client = client;
        this.repository = repository;
        this.state = state;
        this.properties = properties;
        this.clock = clock;
        this.failureLogs = properties.planes().keySet().stream()
            .collect(Collectors.toMap(Function.identity(), inverter -> new FailureLog(log, clock)));
    }

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "${energy.forecast.interval}", timeUnit = TimeUnit.MINUTES)
    Mono<Void> fetch() {
        return ScheduledPass.bounded("pv forecast", LIMIT, () -> Mono.defer(this::fetchOnce));
    }

    private Mono<Void> fetchOnce() {
        return Flux.fromIterable(properties.planes().entrySet())
            .concatMap(plane -> fetchPlane(plane.getKey(), plane.getValue()))
            .then();
    }

    private Mono<Long> fetchPlane(final String inverter, final ForecastProperties.Plane plane) {
        final String what = "Open-Meteo forecast of the " + inverter.toUpperCase(Locale.ROOT) + " plane";
        return Mono.defer(() -> {
            final LocalDateTime now = LocalDateTime.now(clock);
            return client.forecast(plane)
                .flatMap(hours -> Flux.fromIterable(hours)
                    .filter(hour -> hour.hour().isAfter(now))
                    .concatMap(hour -> repository.save(row(inverter, hour, now)))
                    .reduce(0L, Long::sum)
                    //the statement and the row out of the WARN: one kind of failure for FailureLog
                    .onErrorMap(failure -> new IllegalStateException(
                        "The forecast could not be stored: " + failure.getClass().getSimpleName())))
                .doOnNext(saved -> {
                    state.fetched(inverter, now);
                    failureLogs.get(inverter).succeeded(what);
                    log.debug("The {} stored: {} hours", what, saved);
                })
                .onErrorResume(failure -> Mono.fromRunnable(() -> {
                    state.failed(inverter);
                    failureLogs.get(inverter).failed(what, failure);
                }));
        });
    }

    private static PvForecastEntity row(final String inverter, final HourForecast hour, final LocalDateTime fetchedAt) {
        return new PvForecastEntity(hour.hour(), inverter, hour.irradiance(), hour.horizontalIrradiance(),
            hour.cloudCover(), hour.temperature(), hour.snowfall(), fetchedAt);
    }
}

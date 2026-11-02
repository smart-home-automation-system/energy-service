package cloud.cholewa.energy.production;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.api.model.InverterReply;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
import cloud.cholewa.energy.inverter.InverterService;
import cloud.cholewa.energy.solax.FailureLog;
import cloud.cholewa.energy.solax.RunMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

//The inverter that does not produce in the sun (HAS-333): every minute the sky - the sun above the horizon (SunCalendar)
//and the illuminance of the weather station (WeatherClient) - and every inverter as GET /inverters answers it, judged by
//ProductionRules. The SOLAX in EPS or EPS check is not judged (the outage alert covers a lost grid), nor an inverter whose
//reading is stale (the availability alert of its source covers it) - its count starts anew. An illuminance that cannot be
//read, or that weather-service holds for stale, is not known: the sun never shines then (one WARN an hour, FailureLog).
//Kept in source_alert (kind production, V9): the table follows the broker, as the alarm watches - a row is written only
//once its notice was published, so one that failed is due again with the next pass; the open rows are read every pass,
//the counts of the low passes are in memory (a restart counts anew). Bounded at LIMIT
@Slf4j
@Component
public class ProductionWatch {

    static final Duration LIMIT = Duration.ofSeconds(50);
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(10);
    private static final String WHAT = "illuminance of the weather station";
    private static final Set<RunMode> EPS = Set.of(RunMode.EPS, RunMode.EPS_CHECK);
    private static final Map<String, String> WORDS = Map.of("solax", "SOLAX", "sofar", "SOFAR");

    private final SunCalendar sunCalendar;
    private final WeatherClient weatherClient;
    private final InverterService inverterService;
    private final SourceAlertRepository sourceAlertRepository;
    private final NotificationPublisher notificationPublisher;
    private final ProductionProperties properties;
    private final Clock clock;
    private final FailureLog failureLog;
    private final Map<String, LocalDateTime> lowSince = new ConcurrentHashMap<>();

    public ProductionWatch(
        final SunCalendar sunCalendar,
        final WeatherClient weatherClient,
        final InverterService inverterService,
        final SourceAlertRepository sourceAlertRepository,
        final NotificationPublisher notificationPublisher,
        final ProductionProperties properties,
        final Clock clock
    ) {
        this.sunCalendar = sunCalendar;
        this.weatherClient = weatherClient;
        this.inverterService = inverterService;
        this.sourceAlertRepository = sourceAlertRepository;
        this.notificationPublisher = notificationPublisher;
        this.properties = properties;
        this.clock = clock;
        this.failureLog = new FailureLog(log, clock);
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    Mono<Void> check() {
        return ScheduledPass.bounded("production watch", LIMIT, () -> Mono.defer(this::checkOnce));
    }

    private Mono<Void> checkOnce() {
        final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        final boolean sunUp = sunCalendar.of(now.toLocalDate()).up(now);
        return lux()
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .map(lux -> new ProductionRules.Sky(sunUp, lux.orElse(null)))
            .zipWhen(sky -> inverterService.inverters()
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                        "The inverters could not be read for the production watch: {}", failure.toString()))
                    .thenReturn(List.of())))
            .flatMap(read -> sourceAlertRepository.findByClosedAtIsNullAndKindIn(List.of(ProductionRules.KIND))
                .collectMap(SourceAlertEntity::source, Function.identity())
                .flatMap(open -> Flux.fromIterable(read.getT2())
                    .concatMap(inverter -> judge(inverter, read.getT1(), open, now))
                    .then()))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The production of the inverters could not be judged, tried again with the next pass: {}",
                failure.toString())));
    }

    //the illuminance known and fresh, lx; empty when it is not known
    private Mono<Double> lux() {
        return weatherClient.illuminance()
            .doOnNext(read -> failureLog.succeeded(WHAT))
            .filter(read -> !read.stale())
            .map(WeatherClient.Illuminance::lux)
            .onErrorResume(failure -> Mono.fromRunnable(() -> failureLog.failed(WHAT, failure)));
    }

    private Mono<Void> judge(final InverterReply reply, final ProductionRules.Sky sky,
                             final Map<String, SourceAlertEntity> open, final LocalDateTime now) {
        final Optional<ProductionRules.Inverter> judged = Optional.of(reply)
            .filter(read -> WORDS.containsKey(read.id()))
            .filter(read -> !read.stale() && read.pvPower() != null)
            .filter(read -> read.runMode() == null || !EPS.contains(read.runMode()))
            .map(read -> new ProductionRules.Inverter(read.id(), WORDS.get(read.id()), read.pvPower()));
        //an inverter not judged starts its count anew
        return Mono.justOrEmpty(judged)
            .map(inverter -> ProductionRules.decide(inverter, sky, Optional.ofNullable(open.get(inverter.source())),
                lowSince.get(inverter.source()), properties, now))
            .switchIfEmpty(Mono.fromRunnable(() -> lowSince.remove(reply.id())))
            .flatMap(outcome -> act(reply.id(), outcome));
    }

    private Mono<Void> act(final String source, final ProductionRules.Outcome outcome) {
        Optional.ofNullable(outcome.lowSince()).ifPresentOrElse(since -> lowSince.put(source, since),
            () -> lowSince.remove(source));
        return Mono.justOrEmpty(outcome.notice())
            .flatMap(notice -> publish(notice, outcome.text()))
            .flatMap(sent -> Mono.justOrEmpty(outcome.row()))
            .flatMap(sourceAlertRepository::save)
            .then();
    }

    private Mono<Boolean> publish(final ProductionRules.Notice notice, final String text) {
        final Mono<Void> sent = switch (notice) {
            case ALERT -> notificationPublisher.publishAlert(text);
            case REMINDER -> notificationPublisher.publishReminder(text);
            case INFO -> notificationPublisher.publishInfo(text);
        };
        return sent.timeout(NOTICE_TIMEOUT)
            .thenReturn(true)
            .doOnNext(published -> log.info("Production notification published: {}", text))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                "The production notification was not published, tried again with the next pass - {}: {}",
                failure.toString(), text)));
    }
}

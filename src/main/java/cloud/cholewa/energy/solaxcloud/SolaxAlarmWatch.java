package cloud.cholewa.energy.solaxcloud;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
import cloud.cholewa.energy.solax.FailureLog;
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
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

//The alarms of the SOLAX from the SolaX cloud (HAS-329): every energy.solax.cloud.alarm-interval the ongoing alarms of
//the plant against the open ones of source_alert (AlarmRules); a new alarm an alert, one the cloud no longer lists an
//info. The table follows the broker, the pattern of light-service: a row is written only once its notification was
//published, so one that failed is due again with the next pass and a restart neither repeats nor forgets an alarm. A
//read of the cloud that failed decides nothing (one WARN an hour, FailureLog); only the first page of the alarms is
//read - more than one page of ongoing alarms is a WARN. The alarms of energy.solax.cloud.ignored-alarm-codes are left
//out before anything is decided (17 Bat Power Low: the battery at its floor, almost every day)
@Slf4j
@Component
public class SolaxAlarmWatch {

    static final Duration LIMIT = Duration.ofMinutes(1);
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(10);
    private static final String WHAT = "SolaX cloud alarms";

    private final SolaxCloudClient solaxCloudClient;
    private final SourceAlertRepository sourceAlertRepository;
    private final NotificationPublisher notificationPublisher;
    private final SolaxCloudProperties solaxCloudProperties;
    private final Clock clock;
    private final FailureLog failureLog;

    public SolaxAlarmWatch(
        final SolaxCloudClient solaxCloudClient,
        final SourceAlertRepository sourceAlertRepository,
        final NotificationPublisher notificationPublisher,
        final SolaxCloudProperties solaxCloudProperties,
        final Clock clock
    ) {
        this.solaxCloudClient = solaxCloudClient;
        this.sourceAlertRepository = sourceAlertRepository;
        this.notificationPublisher = notificationPublisher;
        this.solaxCloudProperties = solaxCloudProperties;
        this.clock = clock;
        this.failureLog = new FailureLog(log, clock);
    }

    //timeUnit: a bare number of the interval is minutes here too, as SolaxCloudProperties binds it
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${energy.solax.cloud.alarm-interval}",
        timeUnit = TimeUnit.MINUTES)
    Mono<Void> check() {
        return ScheduledPass.bounded("SolaX alarm watch", LIMIT, () -> Mono.defer(this::checkOnce));
    }

    private Mono<Void> checkOnce() {
        return solaxCloudClient.alarms(true)
            .doOnNext(page -> failureLog.succeeded(WHAT))
            .onErrorResume(failure -> Mono.fromRunnable(() -> failureLog.failed(WHAT, failure)))
            .flatMap(page -> sourceAlertRepository.findByClosedAtIsNullAndKindIn(List.of(AlarmRules.KIND))
                .collectList()
                .flatMap(held -> act(AlarmRules.decide(records(page), held,
                    LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS))))
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The alarms of the SOLAX could not be held, tried again with the next pass: {}",
                    failure.toString()))));
    }

    private List<CloudAlarm> records(final CloudAlarm.Page page) {
        final List<CloudAlarm> records = Optional.ofNullable(page.records()).orElse(List.of());
        Optional.ofNullable(page.total())
            .filter(total -> total > records.size())
            .ifPresent(total -> log.warn("The SolaX cloud lists {} ongoing alarms, only the first {} are read", total,
                records.size()));
        return records.stream()
            .filter(alarm -> !solaxCloudProperties.ignoredAlarmCodes().contains(alarm.errorCode()))
            .toList();
    }

    //the ends first, then the new alarms; each list stored only once its notification was published
    private Mono<Void> act(final AlarmRules.Decision decision) {
        return publish(decision.endedText(), notificationPublisher::publishInfo)
            .flatMap(sent -> store(decision.ended()))
            .then(Mono.defer(() -> publish(decision.startedText(), notificationPublisher::publishAlert)
                .flatMap(sent -> store(decision.started()))));
    }

    private Mono<Boolean> publish(final String text, final Function<String, Mono<Void>> publisher) {
        return Mono.justOrEmpty(text)
            .flatMap(words -> publisher.apply(words)
                .timeout(NOTICE_TIMEOUT)
                .thenReturn(true)
                .doOnNext(sent -> log.info("SOLAX alarm notification published: {}", words))
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                    "The notification of the alarms of the SOLAX was not published, tried again with the next pass"
                        + " - {}: {}", failure.toString(), words))));
    }

    private Mono<Void> store(final List<SourceAlertEntity> rows) {
        return Flux.fromIterable(rows)
            .concatMap(sourceAlertRepository::save)
            .then();
    }
}

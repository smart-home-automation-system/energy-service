package cloud.cholewa.energy.sofar;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Function;

//The faults of the SOFAR (HAS-330): after every reading (SofarReader, in its bounded pass) the faults of the last reading
//against the open sofar alarm rows of source_alert (FaultRules); a new fault an alert, one the reading no longer has an
//info - decided at each reading, so a fault of one reading is not replaced unseen by the next. Only a fresh reading
//decides - while the SOFAR is stale a fault is neither new nor over. The table follows the broker, as SolaxAlarmWatch: a
//row is written only once its notification was published, so one that failed is due again with the next reading and a
//restart neither repeats nor forgets a fault. Never errors: a failure is a WARN
@Slf4j
@Component
@RequiredArgsConstructor
public class SofarFaultWatch {

    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(10);

    private final SofarTracker sofarTracker;
    private final SourceAlertRepository sourceAlertRepository;
    private final NotificationPublisher notificationPublisher;
    private final SolarmanProperties properties;
    private final Clock clock;

    Mono<Void> check() {
        return Mono.defer(this::checkOnce);
    }

    private Mono<Void> checkOnce() {
        final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        final SofarState state = sofarTracker.current();
        return Mono.justOrEmpty(state.reading())
            .filter(reading -> !state.isStale(now, properties.staleAfter()))
            .flatMap(reading -> sourceAlertRepository.findByClosedAtIsNullAndSourceAndKind(FaultRules.SOURCE,
                    FaultRules.KIND)
                .collectList()
                .flatMap(held -> act(FaultRules.decide(reading, held, now))))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The faults of the SOFAR could not be held, tried again with the next reading: {}",
                failure.toString())));
    }

    //the ends first, then the new faults; each list stored only once its notification was published
    private Mono<Void> act(final FaultRules.Decision decision) {
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
                .doOnNext(sent -> log.info("SOFAR fault notification published: {}", words))
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                    "The notification of the faults of the SOFAR was not published, tried again with the next reading"
                        + " - {}: {}", failure.toString(), words))));
    }

    private Mono<Void> store(final List<SourceAlertEntity> rows) {
        return Flux.fromIterable(rows)
            .concatMap(sourceAlertRepository::save)
            .then();
    }
}

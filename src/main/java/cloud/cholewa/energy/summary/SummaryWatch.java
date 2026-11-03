package cloud.cholewa.energy.summary;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.solax.FailureLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

//Sends the daily energy summary (HAS-339) once a day at energy.summary.time of the house, as an info (Discord, never an
//SMS): a pass a minute, bounded at LIMIT, the summary due from that time on until it was published. A summary that
//could not be built or published is tried again with the next pass, for SEND_WITHIN after the time - later the day is
//given up with a WARN, a summary of 21:30 at midnight would mislead; a failure that lasts is one WARN an hour (FailureLog,
//"The daily energy summary could not be read"), not one a pass. The day sent is held in memory: a start after the time
//takes today for sent, so a restart never sends a day twice (and loses the day of a pod that was down at the time)
@Slf4j
@Component
public class SummaryWatch {

    static final Duration LIMIT = Duration.ofSeconds(50);
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(10);
    static final Duration SEND_WITHIN = Duration.ofHours(1);
    private static final String WHAT = "daily energy summary";

    private final SummaryService summaryService;
    private final NotificationPublisher notificationPublisher;
    private final SummaryProperties properties;
    private final Clock clock;
    private final FailureLog failureLog;
    //the last day sent, or given up
    private final AtomicReference<LocalDate> done;

    public SummaryWatch(
        final SummaryService summaryService,
        final NotificationPublisher notificationPublisher,
        final SummaryProperties properties,
        final Clock clock
    ) {
        this.summaryService = summaryService;
        this.notificationPublisher = notificationPublisher;
        this.properties = properties;
        this.clock = clock;
        this.failureLog = new FailureLog(log, clock);
        final LocalDateTime now = LocalDateTime.now(clock);
        this.done = new AtomicReference<>(now.toLocalTime().isBefore(properties.time()) ? now.toLocalDate().minusDays(1)
            : now.toLocalDate());
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    Mono<Void> check() {
        return ScheduledPass.bounded("daily summary", LIMIT, () -> Mono.defer(this::checkOnce));
    }

    private Mono<Void> checkOnce() {
        final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
        final LocalDateTime due = now.toLocalDate().atTime(properties.time());
        return Mono.just(now)
            .filter(at -> !at.isBefore(due) && !now.toLocalDate().equals(done.get()))
            .filter(at -> at.isBefore(due.plus(SEND_WITHIN)) || giveUp(at))
            .flatMap(summaryService::summary)
            .flatMap(summary -> notificationPublisher.publishInfo(summary.text())
                .timeout(NOTICE_TIMEOUT)
                .then(Mono.fromRunnable(() -> {
                    done.set(now.toLocalDate());
                    failureLog.succeeded(WHAT);
                    log.info("Daily summary published: {}", summary.text());
                })))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> failureLog.failed(WHAT, failure)));
    }

    //always false: the day is marked done and not sent
    private boolean giveUp(final LocalDateTime now) {
        done.set(now.toLocalDate());
        log.warn("The daily summary of {} was not sent within {} of {} - given up", now.toLocalDate(), SEND_WITHIN,
            properties.time());
        return false;
    }
}

package cloud.cholewa.energy.tauron;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.database.PlannedOutageEntity;
import cloud.cholewa.energy.database.PlannedOutageRepository;
import cloud.cholewa.energy.solax.FailureLog;
import cloud.cholewa.energy.time.HouseTimes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

//The planned outages of Tauron for the house (HAS-337). One pass a minute, bounded at 50 s, so the read and the notices
//never run at once and never write one row twice:
//1. every energy.tauron.interval the announcements of energy.tauron.ahead-days are read and reconciled with
//   planned_outage (AnnouncementRules) - the address resolved to its GAIDs at the first read and kept. A failed read is
//   one WARN an hour (FailureLog) and the next read an interval later; reads failing for alert-after are an alert, their
//   return an info (in memory: a restart counts anew). A row the database does not take makes the next pass read again
//2. every row with a notice due (NoticeRules) is told and then stored as told - a notice not published is tried again
//   with the next pass; each is cut off after 6 s and its failure logged with its text. A told row the database does not
//   take is held in memory and stored first by the next pass, which does nothing else until it is - so a notice is
//   never told twice and a read never works on a row older than its notice
@Slf4j
@Component
public class PlannedOutageWatch {

    static final Duration LIMIT = Duration.ofSeconds(50);
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(6);
    private static final String WHAT = "announcements of Tauron";

    private final TauronClient tauronClient;
    private final PlannedOutageRepository repository;
    private final NotificationPublisher notificationPublisher;
    private final TauronProperties properties;
    private final Clock clock;
    private final AddressMatch match;
    private final NoticeRules noticeRules;
    private final FailureLog failureLog;
    private final AtomicReference<TauronAddress> address = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> lastReadAt = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> failingSince = new AtomicReference<>();
    private final AtomicBoolean alerted = new AtomicBoolean();
    private final AtomicReference<Set<String>> missed = new AtomicReference<>(Set.of());
    private final Map<Long, PlannedOutageEntity> unsaved = new ConcurrentHashMap<>();

    public PlannedOutageWatch(
        final TauronClient tauronClient,
        final PlannedOutageRepository repository,
        final NotificationPublisher notificationPublisher,
        final TauronProperties properties,
        final Clock clock
    ) {
        this.tauronClient = tauronClient;
        this.repository = repository;
        this.notificationPublisher = notificationPublisher;
        this.properties = properties;
        this.clock = clock;
        this.match = new AddressMatch(properties.city(), properties.street());
        this.noticeRules = new NoticeRules(properties.reminderAt(), properties.remindBefore());
        this.failureLog = new FailureLog(log, clock);
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    Mono<Void> watch() {
        return ScheduledPass.bounded("Planned outage watch", LIMIT,
            () -> Mono.defer(this::storeUnsaved)
                .then(Mono.defer(this::readIfDue))
                .then(Mono.defer(this::tell))
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The planned outages told could not be stored, tried again with the next pass: {}",
                    failure.toString()))));
    }

    //the told rows an earlier pass could not store; an error ends the pass
    private Mono<Void> storeUnsaved() {
        return Flux.fromIterable(List.copyOf(unsaved.values()))
            .concatMap(row -> repository.save(row).doOnNext(saved -> unsaved.remove(row.id())))
            .then();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    Mono<Void> readIfDue() {
        final LocalDateTime now = now();
        return Mono.just(now)
            .filter(at -> Optional.ofNullable(lastReadAt.get())
                .map(last -> !at.isBefore(last.plus(properties.interval())))
                .orElse(true))
            .doOnNext(lastReadAt::set)
            .flatMap(this::read);
    }

    private Mono<Void> read(final LocalDateTime now) {
        return address()
            .flatMap(house -> tauronClient.outages(house, now, now.plusDays(properties.aheadDays())))
            .map(answer -> Announcement.of(answer, match, clock.getZone(), now))
            .flatMap(announced -> succeeded().thenReturn(announced))
            .onErrorResume(failure -> failed(failure, now).then(Mono.empty()))
            .flatMap(announced -> store(announced, now));
    }

    private Mono<TauronAddress> address() {
        return Mono.justOrEmpty(address.get())
            .switchIfEmpty(Mono.defer(() -> tauronClient.cities(properties.city())
                .map(towns -> TauronAddress.cityOf(towns, properties.city()))
                .flatMap(city -> tauronClient.streets(properties.street(), city)
                    .map(streets -> new TauronAddress(city, TauronAddress.streetOf(streets, properties.street()),
                        properties.houseNo())))
                .doOnNext(resolved -> {
                    address.set(resolved);
                    log.info("The address of the house found in Tauron's register: town {}, street {}",
                        resolved.cityGaid(), resolved.streetGaid());
                })));
    }

    private Mono<Void> store(final List<Announcement> announced, final LocalDateTime now) {
        return repository.findByClosedAtIsNull()
            .collectList()
            .map(held -> AnnouncementRules.reconcile(held, announced, missed.get(), now))
            .flatMap(result -> Flux.fromIterable(result.rows())
                .concatMap(repository::save)
                .then(Mono.fromRunnable(() -> missed.set(result.missed()))))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> {
                lastReadAt.set(null);
                log.warn("The planned outages could not be stored, read again with the next pass: {}",
                    failure.toString());
            }));
    }

    //the failures forgotten - once their return was told, when they were alerted of; an info that failed is tried again
    //with the next good read
    private Mono<Void> succeeded() {
        return Mono.fromRunnable(() -> failureLog.succeeded(WHAT))
            .then(Mono.justOrEmpty(failingSince.get()))
            .filter(since -> alerted.get())
            .flatMap(since -> publish("Tauron's announcements of planned outages are read again (not read since "
                + HouseTimes.hour(since) + " " + since.toLocalDate() + ").", notificationPublisher::publishInfo))
            .defaultIfEmpty(true)
            .filter(forget -> forget)
            .doOnNext(forget -> {
                alerted.set(false);
                failingSince.set(null);
            })
            .then();
    }

    //the first failure of a run held; the alert once the run lasts alert-after, tried again with every failure until it
    //was published
    private Mono<Void> failed(final Throwable failure, final LocalDateTime now) {
        return Mono.fromRunnable(() -> failureLog.failed(WHAT, failure))
            .then(Mono.fromSupplier(() -> failingSince.updateAndGet(held -> held == null ? now : held)))
            .filter(since -> !alerted.get() && !now.isBefore(since.plus(properties.alertAfter())))
            .flatMap(since -> publish("Tauron's announcements of planned outages not read since "
                + HouseTimes.hour(since) + " " + since.toLocalDate() + " - a planned outage would not be known.",
                notificationPublisher::publishAlert))
            .filter(sent -> sent)
            .doOnNext(alerted::set)
            .then();
    }

    Mono<Void> tell() {
        final LocalDateTime now = now();
        return repository.findByClosedAtIsNullOrNoticeIsNotNull()
            .sort(Comparator.comparing(PlannedOutageEntity::startsAt))
            .concatMap(row -> Mono.justOrEmpty(noticeRules.due(row, now))
                .flatMap(notice -> publish(notice)
                    .filter(sent -> sent)
                    .flatMap(sent -> repository.save(notice.told())
                        .onErrorResume(failure -> Mono.fromRunnable(() -> unsaved.put(notice.told().id(), notice.told()))
                            .then(Mono.error(failure))))))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The notices of the planned outages could not be held, tried again with the next pass: {}",
                failure.toString())));
    }

    private Mono<Boolean> publish(final NoticeRules.Notice notice) {
        return switch (notice.channel()) {
            case ALERT_SMS -> publish(notice.text(), notificationPublisher::publishAlertSms);
            case ALERT -> publish(notice.text(), notificationPublisher::publishAlert);
            case INFO -> publish(notice.text(), notificationPublisher::publishInfo);
            case REMINDER_SMS -> publish(notice.text(), notificationPublisher::publishReminderSms);
            case NONE -> Mono.just(true);
        };
    }

    private Mono<Boolean> publish(final String text, final Function<String, Mono<Void>> publisher) {
        return publisher.apply(text)
            .timeout(NOTICE_TIMEOUT)
            .thenReturn(true)
            .doOnNext(sent -> log.info("Planned outage notice published: {}", text))
            .onErrorResume(failure -> Mono.fromCallable(() -> {
                log.error("The notice of a planned outage was not published, tried again with the next pass - {}: {}",
                    failure.toString(), text);
                return false;
            }));
    }
}

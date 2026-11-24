package cloud.cholewa.energy.frequency;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.api.model.GridReply;
import cloud.cholewa.energy.api.model.PhaseReply;
import cloud.cholewa.energy.database.PlannedOutageEntity;
import cloud.cholewa.energy.database.PlannedOutageRepository;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
import cloud.cholewa.energy.meter.GridService;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.solax.SolaxReading;
import cloud.cholewa.energy.solax.SolaxState;
import cloud.cholewa.energy.solax.SolaxTracker;
import cloud.cholewa.energy.tauron.AddressMatch;
import cloud.cholewa.energy.tauron.Announcement;
import cloud.cholewa.energy.tauron.PlannedOutageMessages;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

//The grid frequency off its band (HAS-503): every 30 s the frequency of the grid meter as GET /grid answers it (the
//state in memory, never a call to the meter) and of the SOLAX, judged by FrequencyRules. On 2026-11-24 a generator of
//Tauron fed the house at 51.0 Hz for a day: both inverters refused to connect and the outage watch, which judges the
//grid by its voltages, saw it present. While the frequency is off the planned outages of Tauron now name the likely cause.
//Kept in source_alert (kind frequency, source meter, V17): the table follows the broker, as the production watch - a row
//is written only once its notice was published, so one that failed is due again with the next pass; a told row the
//database did not take is held and stored first by the next pass, which decides nothing until it is (a notice is never
//told twice); the open row is read every pass, the counts and the range seen are in memory (a restart counts anew), the
//held row too. FrequencyState holds the last reading
//for GET /status and the automatic charge. Bounded at LIMIT
@Slf4j
@Component
public class FrequencyWatch {

    static final Duration LIMIT = Duration.ofSeconds(25);
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(10);

    private final GridService gridService;
    private final SolaxTracker solaxTracker;
    private final SolaxProperties solaxProperties;
    private final SourceAlertRepository sourceAlertRepository;
    private final PlannedOutageRepository plannedOutageRepository;
    private final NotificationPublisher notificationPublisher;
    private final FrequencyProperties properties;
    private final FrequencyState state;
    private final Clock clock;
    private final AtomicReference<FrequencyRules.Memory> memory =
        new AtomicReference<>(FrequencyRules.Memory.EMPTY);
    private final AtomicReference<SourceAlertEntity> unsaved = new AtomicReference<>();

    public FrequencyWatch(
        final GridService gridService,
        final SolaxTracker solaxTracker,
        final SolaxProperties solaxProperties,
        final SourceAlertRepository sourceAlertRepository,
        final PlannedOutageRepository plannedOutageRepository,
        final NotificationPublisher notificationPublisher,
        final FrequencyProperties properties,
        final FrequencyState state,
        final Clock clock
    ) {
        this.gridService = gridService;
        this.solaxTracker = solaxTracker;
        this.solaxProperties = solaxProperties;
        this.sourceAlertRepository = sourceAlertRepository;
        this.plannedOutageRepository = plannedOutageRepository;
        this.notificationPublisher = notificationPublisher;
        this.properties = properties;
        this.state = state;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT40S", fixedDelayString = "PT30S")
    Mono<Void> check() {
        return ScheduledPass.bounded("grid frequency watch", LIMIT, () -> Mono.defer(this::checkOnce));
    }

    //a told row the database did not take is stored first; until it is, the pass decides nothing - a notice is never
    //told twice for one row
    Mono<Void> checkOnce() {
        final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        return gridService.grid()
            .map(grid -> readingOf(grid, solaxTracker.current(), now))
            .doOnNext(this::hold)
            .flatMap(reading -> storeUnsaved().then(Mono.defer(() -> judge(reading, now))))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The grid frequency could not be judged, tried again with the next pass: {}", failure.toString())));
    }

    private Mono<Void> storeUnsaved() {
        return Mono.justOrEmpty(unsaved.get())
            .flatMap(row -> sourceAlertRepository.save(row)
                .doOnNext(saved -> unsaved.compareAndSet(row, null)))
            .then();
    }

    private Mono<Void> judge(final FrequencyRules.Reading reading, final LocalDateTime now) {
        return sourceAlertRepository.findByClosedAtIsNullAndKindIn(List.of(FrequencyRules.KIND))
            .filter(row -> FrequencyRules.SOURCE.equals(row.source()))
            .next()
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .flatMap(open -> causeWhileOff(reading, now)
                .map(cause -> FrequencyRules.decide(reading, open, memory.get(), cause, properties, now)))
            .flatMap(this::act);
    }

    //the reading of the grid meter only while it is not stale; the SOLAX only while its reading is fresh
    FrequencyRules.Reading readingOf(final GridReply grid, final SolaxState solax, final LocalDateTime now) {
        final Map<String, PhaseReply> phases = Optional.of(grid)
            .filter(read -> !read.stale())
            .map(GridReply::phases)
            .orElse(Map.of());
        final Function<String, Optional<PhaseReply>> phase = name -> Optional.ofNullable(phases.get(name));
        final Double solaxHz = Optional.of(solax)
            .filter(read -> !read.isStale(now, solaxProperties.staleAfter()))
            .map(SolaxState::reading)
            .map(SolaxReading::frequency)
            .orElse(null);
        return new FrequencyRules.Reading(
            phase.apply("a").map(PhaseReply::frequency).orElse(null),
            phase.apply("b").map(PhaseReply::frequency).orElse(null),
            phase.apply("c").map(PhaseReply::frequency).orElse(null),
            phase.apply("a").map(PhaseReply::voltage).orElse(null),
            phase.apply("b").map(PhaseReply::voltage).orElse(null),
            phase.apply("c").map(PhaseReply::voltage).orElse(null),
            solaxHz);
    }

    private void hold(final FrequencyRules.Reading reading) {
        final Double hz = reading.hz();
        state.set(new FrequencyState.Snapshot(hz, Optional.ofNullable(hz).map(properties::off).orElse(null)));
    }

    //the likely cause, read from the database only while the frequency is off
    private Mono<String> causeWhileOff(final FrequencyRules.Reading reading, final LocalDateTime now) {
        return Mono.justOrEmpty(reading.hz())
            .filter(properties::off)
            .flatMap(hz -> cause(now))
            .defaultIfEmpty("");
    }

    //the outages of Tauron going on now: a generator of Tauron is the likely cause; none - a generator or a fault. Outages
    //worded the same are told once: Tauron announces one work in several rows, each for other streets of the town (two on
    //2026-11-24, the first real alert named the same outage twice)
    Mono<String> cause(final LocalDateTime now) {
        return plannedOutageRepository.findByClosedAtIsNullAndEndsAtAfterOrderByStartsAt(now)
            .filter(outage -> !outage.startsAt().isAfter(now))
            .map(FrequencyWatch::words)
            .distinct()
            .collect(Collectors.joining("; "))
            .filter(outages -> !outages.isEmpty())
            .map(outages -> "the house is likely fed by a generator of Tauron: " + outages)
            .defaultIfEmpty("a generator or a fault of the grid - no outage of Tauron is announced for now")
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The outages of Tauron could not be read for the frequency notice: {}", failure.toString()))
                .thenReturn("the outages of Tauron could not be read"));
    }

    private static String words(final PlannedOutageEntity outage) {
        final String what = Announcement.PLANNED.equals(outage.kind()) ? "a planned outage" : "a failure";
        final String where = AddressMatch.Scope.STREET.value().equals(outage.scope())
            ? "at the street of the house"
            : "in the town";
        return what + " " + where + " " + PlannedOutageMessages.span(outage);
    }

    //the memory moves on with a quiet pass or a notice published; a notice not published keeps the memory it was
    //decided on, so the next pass decides it again (an info with the time it came back and the range seen)
    private Mono<Void> act(final FrequencyRules.Outcome outcome) {
        return Mono.justOrEmpty(outcome.notice())
            .switchIfEmpty(Mono.fromRunnable(() -> memory.set(outcome.memory())))
            .flatMap(notice -> publish(notice, outcome.text()))
            .doOnNext(sent -> memory.set(outcome.memory()))
            .flatMap(sent -> Mono.justOrEmpty(outcome.row()))
            .flatMap(row -> sourceAlertRepository.save(row)
                .onErrorResume(failure -> Mono.fromRunnable(() -> {
                    unsaved.set(row);
                    log.warn("The told grid frequency episode was not stored, stored first by the next pass: {}",
                        failure.toString());
                })))
            .then();
    }

    private Mono<Boolean> publish(final FrequencyRules.Notice notice, final String text) {
        final Mono<Void> sent = switch (notice) {
            case ALERT -> notificationPublisher.publishAlert(text);
            case REMINDER -> notificationPublisher.publishReminder(text);
            case INFO -> notificationPublisher.publishInfo(text);
        };
        return sent.timeout(NOTICE_TIMEOUT)
            .thenReturn(true)
            .doOnNext(published -> log.info("Grid frequency notification published: {}", text))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                "The grid frequency notification was not published, tried again with the next pass - {}: {}",
                failure.toString(), text)));
    }

    //for the tests: the memory between the passes
    FrequencyRules.Memory memory() {
        return memory.get();
    }
}

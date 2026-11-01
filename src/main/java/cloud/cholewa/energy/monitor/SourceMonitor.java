package cloud.cholewa.energy.monitor;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
import cloud.cholewa.energy.meter.GridMeterState;
import cloud.cholewa.energy.meter.GridMeterTracker;
import cloud.cholewa.energy.meter.MeterProperties;
import cloud.cholewa.energy.solax.SolaxState;
import cloud.cholewa.energy.solax.SolaxTracker;
import cloud.cholewa.shelly.model.EmStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//The monitor of the sources (HAS-332): every minute the SOLAX dongle and the grid meter judged for silence and the
//phases of the meter for an overload by SourceRules, the decision held in memory at once, the notifications sent, then
//the rows of source_alert stored - a row the database does not take is tried again with every pass.
//- The SOLAX is out when its reader failed and it answered nothing for energy.availability.solax-silent-after; it is
//  back with a reading after the episode opened. The cloud read of SolaxCloud is not the dongle.
//- The grid meter is out when its last read over RPC failed and no message nor read came for
//  energy.availability.meter-silent-after - the broker alone going down is no silence of the meter; back with a reading.
//- A phase current counts only from a reading of the meter not older than energy.meter.stale-after.
//After a start nothing is decided before the open episodes are read from the database (tried again with every pass):
//a restart neither repeats an alert nor forgets a return. A notification neither fails nor stalls the pass: each is cut
//off after NOTICE_TIMEOUT and its failure logged with its text; what it would have changed waits for the next pass
@Slf4j
@Component
public class SourceMonitor {

    static final Duration LIMIT = Duration.ofSeconds(50);
    //six notices of one pass at most (a return, an alert, a reminder and a phase each) fit in the bound of the pass
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(6);
    //the alarms of the SOLAX share the table (HAS-329) and are not this monitor's
    static final List<String> KINDS = List.of(Source.Kind.SILENT.wireName(), Source.Kind.OVERLOAD.wireName());

    private final SolaxTracker solaxTracker;
    private final GridMeterTracker gridMeterTracker;
    private final SourceAlertRepository sourceAlertRepository;
    private final NotificationPublisher notificationPublisher;
    private final AvailabilityProperties availabilityProperties;
    private final OverloadProperties overloadProperties;
    private final MeterProperties meterProperties;
    private final Clock clock;
    private final LocalDateTime startedAt;
    private final AtomicReference<SourceWatch> watch = new AtomicReference<>(SourceWatch.NOT_LOADED);

    public SourceMonitor(
        final SolaxTracker solaxTracker,
        final GridMeterTracker gridMeterTracker,
        final SourceAlertRepository sourceAlertRepository,
        final NotificationPublisher notificationPublisher,
        final AvailabilityProperties availabilityProperties,
        final OverloadProperties overloadProperties,
        final MeterProperties meterProperties,
        final Clock clock
    ) {
        this.solaxTracker = solaxTracker;
        this.gridMeterTracker = gridMeterTracker;
        this.sourceAlertRepository = sourceAlertRepository;
        this.notificationPublisher = notificationPublisher;
        this.availabilityProperties = availabilityProperties;
        this.overloadProperties = overloadProperties;
        this.meterProperties = meterProperties;
        this.clock = clock;
        this.startedAt = now();
    }

    SourceWatch current() {
        return watch.get();
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    Mono<Void> check() {
        return ScheduledPass.bounded("source monitor", LIMIT, () -> load().then(Mono.defer(this::step)));
    }

    private Mono<Void> load() {
        return Mono.fromSupplier(watch::get)
            .filter(held -> !held.loaded())
            .flatMap(held -> sourceAlertRepository.findByClosedAtIsNullAndKindIn(KINDS)
                .collectList()
                .doOnNext(rows -> watch.set(SourceWatch.loaded(open(rows))))
                .then()
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The open episodes of the sources could not be read, tried again with the next pass: {}",
                    failure.toString()))));
    }

    private static Map<Source, SourceAlertEntity> open(final List<SourceAlertEntity> rows) {
        final Map<Source, SourceAlertEntity> open = new EnumMap<>(Source.class);
        rows.forEach(row -> Source.of(row.source(), row.kind()).ifPresentOrElse(source -> {
            log.info("The {} episode of {} since {} is still open", row.kind(), row.source(), row.openedAt());
            open.put(source, row);
        }, () -> log.warn("An open episode of a source this version does not know: {} {}", row.source(), row.kind())));
        return open;
    }

    private Mono<Void> step() {
        return Mono.fromSupplier(watch::get)
            .filter(SourceWatch::loaded)
            .flatMap(held -> {
                final LocalDateTime now = now();
                final GridMeterState meter = gridMeterTracker.current();
                final SourceRules.Decision decision = SourceRules.decide(held, silences(meter, now),
                    currents(meter, now), now, availabilityProperties, overloadProperties);
                watch.set(decision.next());
                return Flux.fromIterable(decision.notices())
                    .concatMap(this::send)
                    .then(Mono.defer(this::store));
            });
    }

    private Map<Source, Silence> silences(final GridMeterState meter, final LocalDateTime now) {
        final Map<Source, Silence> silences = new EnumMap<>(Source.class);
        silences.put(Source.SOLAX, solax(solaxTracker.current(), now));
        silences.put(Source.METER, meter(meter, now));
        return silences;
    }

    private Silence solax(final SolaxState state, final LocalDateTime now) {
        if (Boolean.TRUE.equals(state.reachable())) {
            return Silence.heard(state.readingAt());
        }
        if (Boolean.FALSE.equals(state.reachable())) {
            return outAfter(Optional.ofNullable(state.readingAt()).orElse(startedAt),
                availabilityProperties.solaxSilentAfter(), now);
        }
        return Silence.NOT_KNOWN;
    }

    private Silence meter(final GridMeterState state, final LocalDateTime now) {
        final LocalDateTime last = state.lastReadingAt();
        final Duration bound = availabilityProperties.meterSilentAfter();
        if (last != null && last.plus(bound).isAfter(now)) {
            return Silence.heard(last);
        }
        if (Boolean.FALSE.equals(state.rpcReachable())) {
            return outAfter(Optional.ofNullable(last).orElse(startedAt), bound, now);
        }
        return Silence.NOT_KNOWN;
    }

    private static Silence outAfter(final LocalDateTime heardAt, final Duration bound, final LocalDateTime now) {
        return heardAt.plus(bound).isAfter(now) ? Silence.NOT_KNOWN : Silence.out(heardAt);
    }

    //the currents of the phases from a reading not older than energy.meter.stale-after; none known otherwise
    private Map<Source, Double> currents(final GridMeterState state, final LocalDateTime now) {
        final Map<Source, Double> currents = new EnumMap<>(Source.class);
        final EmStatus readings = state.readings();
        if (readings == null || state.readingsAt() == null
            || state.readingsAt().isBefore(now.minus(meterProperties.staleAfter()))) {
            return currents;
        }
        Optional.ofNullable(readings.getaCurrent()).ifPresent(value -> currents.put(Source.PHASE_A, value));
        Optional.ofNullable(readings.getbCurrent()).ifPresent(value -> currents.put(Source.PHASE_B, value));
        Optional.ofNullable(readings.getcCurrent()).ifPresent(value -> currents.put(Source.PHASE_C, value));
        return currents;
    }

    //published, then what the notice changes is held - a notice that failed changes nothing and is due again
    private Mono<Void> send(final SourceRules.Notice notice) {
        final Mono<Void> publish = switch (notice.type()) {
            case ALERT -> notificationPublisher.publishAlert(notice.text());
            case REMINDER, OVERLOAD -> notificationPublisher.publishReminder(notice.text());
            case INFO, OVERLOAD_END -> notificationPublisher.publishInfo(notice.text());
        };
        return publish
            .timeout(NOTICE_TIMEOUT)
            .then(Mono.fromRunnable(() -> {
                log.info("Source notification published: {}", notice.text());
                watch.updateAndGet(held -> applied(held, notice));
            }))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                "The notification of the sources was not published, tried again with the next pass - {}: {}",
                failure.toString(), notice.text())));
    }

    private static SourceWatch applied(final SourceWatch held, final SourceRules.Notice notice) {
        SourceWatch next = held;
        for (final SourceRules.Row row : notice.rows()) {
            next = next.with(row.entity(), row.source());
        }
        return next;
    }

    private Mono<Void> store() {
        return Flux.fromIterable(watch.get().unsaved())
            .concatMap(unsaved -> sourceAlertRepository.save(unsaved)
                .doOnNext(saved -> watch.updateAndGet(held -> held.stored(unsaved, saved)))
                .then()
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The {} episode of {} since {} could not be stored, tried again with the next pass: {}",
                    unsaved.kind(), unsaved.source(), unsaved.openedAt(), failure.toString()))))
            .then();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}

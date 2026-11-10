package cloud.cholewa.energy.elicznik;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.database.ElicznikHourEntity;
import cloud.cholewa.energy.database.ElicznikMeterEntity;
import cloud.cholewa.energy.database.ElicznikRepository;
import cloud.cholewa.energy.database.EnergyDayEntity;
import cloud.cholewa.energy.database.EnergyDayRepository;
import cloud.cholewa.energy.error.EnergyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

//The readings of the eLicznik portal (HAS-340): every morning at energy.elicznik.fetch-cron (06:00) the days of the last
//retry-days not stored whole and the registers not stored are asked for, with ONE login - and no login at all when nothing
//is missing, so the portal is asked at most once a day. A day the portal does not serve whole yet is asked again the next
//morning, for retry-days; then it is given up with a WARN. A day stored is compared with the 3EM (energy_day): a share
//apart above warn-above is a WARN, never an alert. A failure is a WARN; failed mornings for alert-after an alert ("the
//portal changed or the password expired"), the first good one after it an info - the run of failures in memory, a
//restart counts anew. One fetch at a time; bounded at LIMIT. Never logs the account, the password or an answer
@Slf4j
@Component
public class ElicznikFetch {

    static final Duration LIMIT = Duration.ofMinutes(5);
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(6);

    private final ElicznikClient client;
    private final ElicznikRepository repository;
    private final EnergyDayRepository energyDayRepository;
    private final NotificationPublisher notificationPublisher;
    private final ElicznikProperties properties;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<LocalDateTime> lastLoginAt = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> failingSince = new AtomicReference<>();
    private final AtomicBoolean alerted = new AtomicBoolean();

    public ElicznikFetch(
        final ElicznikClient client,
        final ElicznikRepository repository,
        final EnergyDayRepository energyDayRepository,
        final NotificationPublisher notificationPublisher,
        final ElicznikProperties properties,
        final Clock clock
    ) {
        this.client = client;
        this.repository = repository;
        this.energyDayRepository = energyDayRepository;
        this.notificationPublisher = notificationPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    //what a fetch stored: the days whole and the days of the registers; logins 0 when nothing was missing
    public record Outcome(int days, int registers, int logins) {
    }

    @Scheduled(cron = "${energy.elicznik.fetch-cron}")
    Mono<Void> morning() {
        return ScheduledPass.bounded("eLicznik fetch", LIMIT, () -> fetch()
            .then()
            .onErrorResume(ElicznikException.class, failure -> Mono.empty())
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The eLicznik fetch failed, tried again the next morning: {}", failure.toString()))));
    }

    //POST /elicznik/fetch: refused while a fetch runs or within fetch-every of the last login; a failure answered
    public Mono<Outcome> byHand() {
        final LocalDateTime now = now();
        return Mono.justOrEmpty(lastLoginAt.get())
            .filter(last -> now.isBefore(last.plus(properties.fetchEvery())))
            .flatMap(last -> Mono.<Outcome>error(EnergyException.fetchTooSoon("The eLicznik portal was logged in to at "
                + last.toLocalTime().truncatedTo(ChronoUnit.MINUTES) + " - not again before "
                + last.plus(properties.fetchEvery()).toLocalTime().truncatedTo(ChronoUnit.MINUTES))))
            .switchIfEmpty(Mono.defer(this::fetch))
            .timeout(LIMIT)
            .onErrorMap(failure -> !(failure instanceof EnergyException),
                failure -> EnergyException.elicznikUnavailable(failure instanceof ElicznikException ? failure.getMessage()
                    : "The eLicznik fetch did not end: " + failure.getClass().getSimpleName()));
    }

    //one at a time; an error is the failure the caller sees, logged and counted here
    private Mono<Outcome> fetch() {
        return Mono.fromSupplier(() -> running.compareAndSet(false, true))
            .filter(Boolean::booleanValue)
            .switchIfEmpty(Mono.error(() -> EnergyException.fetchTooSoon("An eLicznik fetch runs")))
            .flatMap(acquired -> Mono.defer(this::fetchOnce)
                .flatMap(outcome -> succeeded().thenReturn(outcome))
                .onErrorResume(ElicznikException.class, failure -> failed(failure).then(Mono.error(failure)))
                .doFinally(signal -> running.set(false)));
    }

    private Mono<Outcome> fetchOnce() {
        final LocalDateTime now = now();
        final LocalDate today = now.toLocalDate();
        final LocalDate first = today.minusDays(properties.retryDays());
        final LocalDate last = today.minusDays(1);
        return repository.hoursADay(first, last)
            .collectMap(ElicznikRepository.DayHours::day, ElicznikRepository.DayHours::hours)
            .zipWhen(stored -> repository.meterDays(first, last).collect(Collectors.toSet()))
            .flatMap(stored -> {
                final List<LocalDate> missingDays = missingDays(first, last, stored.getT1());
                final TreeSet<LocalDate> missingRegisters = missingRegisters(first, last, stored.getT2());
                return Mono.just(missingDays.isEmpty() && missingRegisters.isEmpty())
                    .filter(Boolean::booleanValue)
                    .map(nothing -> {
                        log.info("The eLicznik readings of {} to {} are stored - the portal not asked", first, last);
                        return new Outcome(0, 0, 0);
                    })
                    .switchIfEmpty(Mono.defer(() -> fetchMissing(missingDays, missingRegisters, first, now)));
            });
    }

    //ONE login for everything missing; the attempt counts, a refused one too: a wrong password tried again and again
    //blocks the account
    private Mono<Outcome> fetchMissing(final List<LocalDate> missingDays, final TreeSet<LocalDate> missingRegisters,
                                       final LocalDate first, final LocalDateTime now) {
        return Mono.fromRunnable(() -> lastLoginAt.set(now))
            .then(Mono.defer(client::login))
            .flatMap(session -> Flux.fromIterable(missingDays)
                .concatMap(day -> fetchDay(session, day, first, now))
                .reduce(0, Integer::sum)
                .flatMap(days -> fetchRegisters(session, missingRegisters, now)
                    .map(registers -> new Outcome(days, registers, 1))))
            .doOnNext(outcome -> log.info("The eLicznik readings fetched: {} day(s) and {} register(s) stored",
                outcome.days(), outcome.registers()));
    }

    private List<LocalDate> missingDays(final LocalDate first, final LocalDate last, final Map<LocalDate, Long> stored) {
        return first.datesUntil(last.plusDays(1))
            .filter(day -> stored.getOrDefault(day, 0L) != ElicznikDay.hoursOf(day, clock.getZone()))
            .toList();
    }

    private static TreeSet<LocalDate> missingRegisters(final LocalDate first, final LocalDate last,
                                                       final Set<LocalDate> stored) {
        return first.datesUntil(last.plusDays(1))
            .filter(day -> !stored.contains(day))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    //1 when the day was stored whole, 0 when the portal does not serve it whole yet
    private Mono<Integer> fetchDay(final ElicznikClient.Session session, final LocalDate day, final LocalDate first,
                                   final LocalDateTime now) {
        return client.hours(session, day, false)
            .zipWhen(imported -> client.hours(session, day, true))
            .map(both -> ElicznikDay.whole(day, clock.getZone(), both.getT1(), both.getT2(), now))
            .flatMap(whole -> whole
                .map(rows -> Flux.fromIterable(rows).concatMap(repository::save).then(compare(day, rows)).thenReturn(1))
                .orElseGet(() -> Mono.fromCallable(() -> {
                    if (day.equals(first)) {
                        log.warn("The eLicznik day {} is not served whole after {} mornings - given up", day,
                            properties.retryDays());
                    } else {
                        log.info("The eLicznik day {} is not served whole yet - asked again the next morning", day);
                    }
                    return 0;
                })));
    }

    //the registers of the days missing, both read for the range from the first to the last of them
    private Mono<Integer> fetchRegisters(final ElicznikClient.Session session, final TreeSet<LocalDate> missing,
                                         final LocalDateTime now) {
        return Mono.just(missing)
            .filter(days -> !days.isEmpty())
            .flatMap(days -> client.registers(session, days.first(), days.last(), false)
                .zipWhen(imported -> client.registers(session, days.first(), days.last(), true)))
            .flatMapMany(both -> {
                final Map<LocalDate, BigDecimal> exported = both.getT2().stream()
                    .collect(Collectors.toMap(ElicznikAnswers.Register::day, ElicznikAnswers.Register::kwh,
                        (firstValue, second) -> firstValue));
                return Flux.fromIterable(both.getT1())
                    .filter(register -> missing.contains(register.day()) && exported.containsKey(register.day()))
                    .map(register -> new ElicznikMeterEntity(register.day(), register.kwh(),
                        exported.get(register.day()), now));
            })
            .concatMap(repository::save)
            .reduce(0, (count, saved) -> count + saved.intValue())
            .defaultIfEmpty(0);
    }

    //the day of the portal against the 3EM of the same day, when that day was closed and knows it
    private Mono<Void> compare(final LocalDate day, final List<ElicznikHourEntity> rows) {
        final BigDecimal imported = sum(rows, ElicznikHourEntity::imported);
        final BigDecimal exported = sum(rows, ElicznikHourEntity::exported);
        return energyDayRepository.findById(day)
            .doOnNext(house -> {
                warnApart(day, "import", imported, house.imported());
                warnApart(day, "export", exported, house.exported());
            })
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The eLicznik day {} could not be compared with the 3EM: {}", day, failure.toString())));
    }

    private void warnApart(final LocalDate day, final String what, final BigDecimal operator, final Double house) {
        Optional.ofNullable(house)
            .map(BigDecimal::valueOf)
            .flatMap(meter -> share(operator, meter)
                .filter(apart -> apart.compareTo(properties.warnAbove()) > 0)
                .map(apart -> "The eLicznik " + what + " of " + day + " is " + operator + " kWh, the 3EM "
                    + meter.setScale(2, RoundingMode.HALF_UP) + " kWh - "
                    + apart.movePointRight(2).setScale(1, RoundingMode.HALF_UP) + " % apart"))
            .ifPresent(log::warn);
    }

    //|a - b| / the larger; none for two values of 0
    static Optional<BigDecimal> share(final BigDecimal operator, final BigDecimal meter) {
        final BigDecimal larger = operator.max(meter);
        return larger.signum() <= 0 ? Optional.empty()
            : Optional.of(operator.subtract(meter).abs().divide(larger, 4, RoundingMode.HALF_UP));
    }

    private static BigDecimal sum(final List<ElicznikHourEntity> rows, final Function<ElicznikHourEntity, BigDecimal> of) {
        return rows.stream().map(of).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    //the run of failures forgotten - once its return was told, when it was alerted of
    private Mono<Void> succeeded() {
        return Mono.justOrEmpty(failingSince.get())
            .filter(since -> alerted.get())
            .flatMap(since -> publish("The eLicznik readings of Tauron are fetched again (failing since "
                + since.toLocalDate() + ").", notificationPublisher::publishInfo))
            .defaultIfEmpty(true)
            .filter(forget -> forget)
            .doOnNext(forget -> {
                alerted.set(false);
                failingSince.set(null);
            })
            .then();
    }

    //a WARN; the alert once the failures last alert-after, tried again with every failure until it was published
    private Mono<Void> failed(final ElicznikException failure) {
        final LocalDateTime now = now();
        return Mono.fromRunnable(() -> log.warn("The eLicznik readings could not be fetched: {}", failure.getMessage()))
            .then(Mono.fromSupplier(() -> failingSince.updateAndGet(held -> held == null ? now : held)))
            .filter(since -> !alerted.get() && !now.isBefore(since.plus(properties.alertAfter())))
            .flatMap(since -> publish("The eLicznik readings of Tauron not fetched since " + since.toLocalDate()
                + " - the portal changed or the password expired: " + failure.getMessage(),
                notificationPublisher::publishAlert))
            .filter(sent -> sent)
            .doOnNext(alerted::set)
            .then();
    }

    private Mono<Boolean> publish(final String text, final Function<String, Mono<Void>> publisher) {
        return publisher.apply(text)
            .timeout(NOTICE_TIMEOUT)
            .thenReturn(true)
            .doOnNext(sent -> log.info("eLicznik notice published: {}", text))
            .onErrorResume(failure -> Mono.fromCallable(() -> {
                log.error("The eLicznik notice was not published, tried again with the next fetch - {}: {}",
                    failure.toString(), text);
                return false;
            }));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}

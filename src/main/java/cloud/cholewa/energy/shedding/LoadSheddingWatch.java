package cloud.cholewa.energy.shedding;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.energy.database.EnergyAutomationEntity;
import cloud.cholewa.energy.database.EnergyAutomationRepository;
import cloud.cholewa.energy.database.LoadSheddingEntity;
import cloud.cholewa.energy.database.LoadSheddingRepository;
import cloud.cholewa.energy.outage.GridOutageService;
import lombok.RequiredArgsConstructor;
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
import java.util.concurrent.atomic.AtomicReference;

//Watches the load shedding (HAS-523): every 15 s, after the watch of the grid, SheddingRules on the grid as GET /status
//answers it - held in memory at once, then the row of load_shedding stored, as GridOutageWatch does: the database is
//managed, outside the house, and in an outage the internet may be down with the grid; a row it does not take is tried
//again with every pass. Nothing is decided before the open episode and the switch were read from the database (a failed
//read is tried again with the next pass): a restart during an episode continues it. The switch is held apart, set by the
//API at once (a pass in flight never undoes it). The pass is bounded below its delay. It switches nothing yet - the
//plugs, the messages and aircon-service follow the state (epic HAS-520)
@Slf4j
@Component
@RequiredArgsConstructor
public class LoadSheddingWatch {

    static final Duration LIMIT = Duration.ofSeconds(14);

    private final LoadSheddingRepository repository;
    private final EnergyAutomationRepository automationRepository;
    private final GridOutageService gridOutageService;
    private final SheddingProperties properties;
    private final Clock clock;
    private final AtomicReference<SheddingState> state = new AtomicReference<>(SheddingState.NOT_LOADED);
    private final AtomicReference<Boolean> enabled = new AtomicReference<>();

    //the load shedding now - empty until the episode and the switch were read after a start: never read as off
    public Optional<Shedding> current() {
        final SheddingState held = state.get();
        if (!held.loaded() || enabled.get() == null) {
            return Optional.empty();
        }
        return Optional.of(held.open() == null ? Shedding.OFF
            : new Shedding(true, held.open().startedAt(), SheddingRules.endsAt(held, properties.stableAfter())));
    }

    //the switch as the API stored it - the next pass ends an episode that lasts
    public void switched(final boolean on) {
        enabled.set(on);
    }

    SheddingState held() {
        return state.get();
    }

    @Scheduled(initialDelayString = "PT25S", fixedDelayString = "PT15S")
    Mono<Void> watch() {
        return ScheduledPass.bounded("Load shedding watch", LIMIT, () -> load().then(Mono.defer(this::step)));
    }

    //the open episode and the switch, once after a start; the switch set by the API meanwhile is newer and stays
    private Mono<Void> load() {
        return Mono.fromSupplier(state::get)
            .filter(held -> !held.loaded())
            .flatMap(held -> repository.findFirstByEndedAtIsNull()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .zipWhen(open -> automationRepository.findById(EnergyAutomationEntity.ROW_ID)
                    .switchIfEmpty(Mono.error(() -> new IllegalStateException("energy_automation has no row"))))
                .doOnNext(read -> {
                    enabled.compareAndSet(null, read.getT2().loadShedding());
                    read.getT1().ifPresent(open -> log.info("The load shedding since {} is still on", open.startedAt()));
                    state.set(SheddingState.loaded(read.getT1().orElse(null)));
                })
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The load shedding could not be read, tried again with the next pass: {}", failure.toString()))))
            .then();
    }

    //decided in memory and held at once, then the rows stored - those earlier passes could not store first, then the row
    //of this pass
    private Mono<Void> step() {
        return Mono.fromSupplier(state::get)
            .filter(SheddingState::loaded)
            .zipWith(Mono.justOrEmpty(enabled.get()))
            .flatMap(read -> {
                final SheddingRules.Decision decision = SheddingRules.decide(read.getT1(), read.getT2(),
                    gridOutageService.grid(), LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS),
                    properties.stableAfter());
                final SheddingState next = decision.next().withUnsaved(decision.row());
                state.set(next);
                logChange(decision);
                return store(next.unsaved());
            });
    }

    private static void logChange(final SheddingRules.Decision decision) {
        switch (decision.change()) {
            case STARTED -> log.info("Load shedding on: the grid is lost");
            case ENDED -> log.info("Load shedding over ({}), on since {}", decision.row().endedBy(),
                decision.row().startedAt());
            case NONE -> {
                //nothing changed
            }
        }
    }

    //one after the other, the oldest first; the first the database does not take ends the pass - it and the rows behind
    //it are tried again with the next pass, in their order. A row waiting behind one of its episode just stored takes
    //its id from it, so the end of an episode updates its row instead of inserting a second one
    private Mono<Void> store(final List<LoadSheddingEntity> rows) {
        return Flux.fromIterable(rows)
            .concatMap(row -> Mono.fromSupplier(() -> current(row))
                .flatMap(unsaved -> repository.save(unsaved)
                    .doOnNext(saved -> state.updateAndGet(held -> held.stored(unsaved, saved)))
                    .onErrorMap(failure -> new UnsavedRow(unsaved, failure))))
            .then()
            .onErrorResume(UnsavedRow.class, failure -> Mono.fromRunnable(() -> log.warn(
                "The load shedding since {} could not be stored, tried again with the next pass: {}",
                failure.row.startedAt(), failure.getCause().toString())));
    }

    //the row as it waits now - an earlier save of its episode may have given it its id
    private LoadSheddingEntity current(final LoadSheddingEntity row) {
        return state.get().unsaved().stream()
            .filter(waiting -> waiting.startedAt().equals(row.startedAt()))
            .findFirst()
            .orElse(row);
    }

    private static final class UnsavedRow extends RuntimeException {

        private final transient LoadSheddingEntity row;

        UnsavedRow(final LoadSheddingEntity row, final Throwable cause) {
            super(cause);
            this.row = row;
        }
    }
}

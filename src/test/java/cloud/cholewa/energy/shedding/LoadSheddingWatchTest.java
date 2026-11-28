package cloud.cholewa.energy.shedding;

import cloud.cholewa.energy.api.model.GridStateReply;
import cloud.cholewa.energy.database.EnergyAutomationEntity;
import cloud.cholewa.energy.database.EnergyAutomationRepository;
import cloud.cholewa.energy.database.LoadSheddingEntity;
import cloud.cholewa.energy.database.LoadSheddingRepository;
import cloud.cholewa.energy.outage.GridOutageService;
import cloud.cholewa.energy.solax.SolaxSamples;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the watch of the load shedding (HAS-523): nothing before the episode and the switch were read, memory first and the row
//stored after, a row the database did not take tried again, an episode open at a start continued, the pass bounded
class LoadSheddingWatchTest {

    private final SolaxSamples.MovableClock clock = new SolaxSamples.MovableClock();
    private final LoadSheddingRepository repository = mock(LoadSheddingRepository.class);
    private final EnergyAutomationRepository automation = mock(EnergyAutomationRepository.class);
    private final GridOutageService gridOutageService = mock(GridOutageService.class);
    private final LoadSheddingWatch watch = new LoadSheddingWatch(repository, automation, gridOutageService,
        new SheddingProperties(Duration.ofMinutes(10)), clock);
    private final AtomicLong ids = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        when(repository.findFirstByEndedAtIsNull()).thenReturn(Mono.empty());
        when(repository.save(any())).thenAnswer(call -> {
            final LoadSheddingEntity row = call.getArgument(0);
            return Mono.just(row.id() != null ? row : row.withId(ids.getAndIncrement()));
        });
        switchStored(true);
    }

    private void switchStored(final boolean on) {
        when(automation.findById(EnergyAutomationEntity.ROW_ID)).thenReturn(Mono.just(
            new EnergyAutomationEntity(1, false, 100, 5.0, 60, null, on)));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    //the grid as GET /status answers it now, then the pass, then 15 s on
    private void pass(final GridStateReply grid) {
        when(gridOutageService.grid()).thenReturn(grid);
        watch.watch().as(StepVerifier::create).verifyComplete();
        clock.moveBy(Duration.ofSeconds(15));
    }

    private GridStateReply lost() {
        return new GridStateReply(GridStateReply.LOST, now().minusSeconds(30));
    }

    @Test
    void should_start_with_the_grid_lost_and_end_ten_minutes_after_its_return() {
        final LocalDateTime start = now();
        pass(lost());

        assertThat(watch.current()).contains(new Shedding(true, start, null));
        assertThat(watch.held().open().id()).isEqualTo(1L);

        final LocalDateTime back = now();
        pass(new GridStateReply(GridStateReply.PRESENT, back));
        assertThat(watch.current()).contains(new Shedding(true, start, back.plusMinutes(10)));

        clock.moveBy(Duration.ofMinutes(10));
        pass(new GridStateReply(GridStateReply.PRESENT, back));

        assertThat(watch.current()).contains(Shedding.OFF);
        verify(repository).save(new LoadSheddingEntity(1L, start, back.plusSeconds(15).plusMinutes(10),
            LoadSheddingEntity.BY_GRID));
    }

    //never read as off: nothing is known before the episode and the switch were read, a failed read tried again
    @Test
    void should_decide_nothing_until_the_episode_and_the_switch_are_read() {
        when(repository.findFirstByEndedAtIsNull())
            .thenReturn(Mono.error(new IllegalStateException("database down")))
            .thenReturn(Mono.empty());

        pass(lost());
        assertThat(watch.current()).isEmpty();
        verify(repository, never()).save(any());

        pass(lost());
        assertThat(watch.current()).map(Shedding::active).contains(true);
    }

    //a restart during an episode continues it - the grid back ends it, no second row
    @Test
    void should_continue_an_episode_open_at_a_start() {
        final LoadSheddingEntity open = new LoadSheddingEntity(7L, now().minusHours(1), null, null);
        when(repository.findFirstByEndedAtIsNull()).thenReturn(Mono.just(open));

        pass(lost());

        assertThat(watch.current()).contains(new Shedding(true, open.startedAt(), null));
        verify(repository, never()).save(any());
    }

    //memory first: the database not taking the row holds nothing back, the row is stored by a later pass
    @Test
    void should_hold_the_start_without_the_database_and_store_the_row_later() {
        doReturn(Mono.error(new IllegalStateException("database down")))
            .doAnswer(call -> Mono.just(((LoadSheddingEntity) call.getArgument(0)).withId(5L)))
            .when(repository).save(any());

        pass(lost());
        assertThat(watch.current()).map(Shedding::active).contains(true);
        assertThat(watch.held().unsaved()).hasSize(1);

        pass(lost());
        assertThat(watch.held().unsaved()).isEmpty();
        assertThat(watch.held().open().id()).isEqualTo(5L);
        verify(repository, times(2)).save(any());
    }

    //the /code-review of HAS-523: the database down from the end of an episode to the start of the next - the end is
    //stored first, as an update of its row, then the new episode; never lost to it
    @Test
    void should_store_an_end_not_taken_before_the_start_that_follows_it() {
        final LocalDateTime start = now();
        pass(lost());
        final Answer<Mono<LoadSheddingEntity>> answer = call -> {
            final LoadSheddingEntity row = call.getArgument(0);
            return Mono.just(row.id() != null ? row : row.withId(ids.getAndIncrement()));
        };
        doReturn(Mono.error(new IllegalStateException("database down"))).when(repository).save(any());

        final LocalDateTime back = now();
        pass(new GridStateReply(GridStateReply.PRESENT, back));
        clock.moveBy(Duration.ofMinutes(10));
        final LocalDateTime end = now();
        pass(new GridStateReply(GridStateReply.PRESENT, back));
        final LocalDateTime second = now();
        pass(lost());
        assertThat(watch.held().unsaved()).hasSize(2);

        doAnswer(answer).when(repository).save(any());
        pass(lost());

        //the end tried with every pass while the database was down, then the end and the start, in that order
        final ArgumentCaptor<LoadSheddingEntity> saved = ArgumentCaptor.forClass(LoadSheddingEntity.class);
        verify(repository, atLeast(2)).save(saved.capture());
        final List<LoadSheddingEntity> rows = saved.getAllValues();
        assertThat(rows.subList(rows.size() - 2, rows.size())).containsExactly(
            new LoadSheddingEntity(1L, start, end, LoadSheddingEntity.BY_GRID), LoadSheddingEntity.started(second));
        assertThat(watch.held().unsaved()).isEmpty();
        assertThat(watch.held().open()).isEqualTo(new LoadSheddingEntity(2L, second, null, null));
    }

    //the switch turned off by the API ends an episode with the next pass
    @Test
    void should_end_an_episode_when_switched_off() {
        pass(lost());
        watch.switched(false);
        pass(lost());

        assertThat(watch.current()).contains(Shedding.OFF);
        verify(repository).save(new LoadSheddingEntity(1L, now().minusSeconds(30), now().minusSeconds(15),
            LoadSheddingEntity.BY_SWITCH));
    }

    //a switch set by the API before the first read is newer than the stored one read by that pass
    @Test
    void should_keep_a_switch_set_before_the_first_read() {
        watch.switched(false);
        pass(lost());

        assertThat(watch.current()).contains(Shedding.OFF);
        verify(repository, never()).save(any());
    }

    @Test
    void should_start_nothing_with_the_switch_off() {
        switchStored(false);

        pass(lost());

        assertThat(watch.current()).isEqualTo(Optional.of(Shedding.OFF));
        verify(repository, never()).save(any());
    }

    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        when(gridOutageService.grid()).thenReturn(lost());
        doReturn(Mono.never()).when(repository).findFirstByEndedAtIsNull();

        StepVerifier.withVirtualTime(watch::watch)
            .expectSubscription()
            .expectNoEvent(LoadSheddingWatch.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }
}

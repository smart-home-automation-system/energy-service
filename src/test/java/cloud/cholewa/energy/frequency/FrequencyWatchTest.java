package cloud.cholewa.energy.frequency;

import cloud.cholewa.commons.notification.NotificationPublisher;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the watch of the grid frequency (HAS-503) on its collaborators: the frequency of the grid meter counted pass by pass and
//told after off-after with the outages of Tauron as the cause, a stale meter never deciding, a row only once told, a
//notice not published decided again, FrequencyState held for the status and the automation
class FrequencyWatchTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 24, 7, 5);

    private final Instant[] now = {T0.atZone(HOUSE).toInstant()};
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return HOUSE;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now[0];
        }
    };
    private final GridService gridService = mock(GridService.class);
    private final SolaxTracker solaxTracker = mock(SolaxTracker.class);
    private final SourceAlertRepository alerts = mock(SourceAlertRepository.class);
    private final PlannedOutageRepository outages = mock(PlannedOutageRepository.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final FrequencyState state = new FrequencyState();
    private final FrequencyWatch watch = new FrequencyWatch(gridService, solaxTracker,
        new SolaxProperties(Duration.ofSeconds(15), Duration.ofMinutes(1), 26.5), alerts, outages, publisher,
        new FrequencyProperties(49.8, 50.2, Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofHours(1)),
        state, clock);
    private final List<SourceAlertEntity> saved = new ArrayList<>();

    private static GridReply grid(final double hz, final boolean stale) {
        final Map<String, PhaseReply> phases = new LinkedHashMap<>();
        phases.put("a", new PhaseReply(231.5, 2.3, 264.2, 532.4, 0.51, hz));
        phases.put("b", new PhaseReply(227.0, 4.7, 679.7, 1069.1, 0.64, hz));
        phases.put("c", new PhaseReply(229.6, 3.6, 558.3, 819.9, 0.69, hz));
        return new GridReply(phases, 1502.2, 1715.3, 2176.5, null, T0, stale);
    }

    private static PlannedOutageEntity outage(final String scope) {
        return new PlannedOutageEntity(4L, "o-4", "planned", scope, T0.withHour(6).withMinute(0),
            T0.withHour(18).withMinute(0), "Testowo ul. Przykładowa", T0, T0, null, null, T0, null, null, null, null,
            false, null);
    }

    private void meter(final double hz) {
        when(gridService.grid()).thenReturn(Mono.just(grid(hz, false)));
    }

    private void pass() {
        watch.checkOnce().as(StepVerifier::create).verifyComplete();
    }

    private void minutesOf(final int count) {
        for (int minute = 0; minute <= count; minute++) {
            pass();
            now[0] = now[0].plus(Duration.ofMinutes(1));
        }
    }

    @BeforeEach
    void setUp() {
        when(solaxTracker.current()).thenReturn(new SolaxState(134L, null, null, null));
        when(alerts.findByClosedAtIsNullAndKindIn(List.of("frequency"))).thenAnswer(call -> Flux.fromIterable(
            saved.stream().filter(row -> row.closedAt() == null).toList()));
        when(alerts.save(any())).thenAnswer(call -> {
            final SourceAlertEntity row = call.getArgument(0);
            final SourceAlertEntity stored = row.id() == null
                ? new SourceAlertEntity(1L, row.source(), row.kind(), row.openedAt(), row.lastToldAt(), row.closedAt(),
                row.detail())
                : row;
            saved.removeIf(old -> old.id().equals(stored.id()));
            saved.add(stored);
            return Mono.just(stored);
        });
        when(outages.findByClosedAtIsNullAndEndsAtAfterOrderByStartsAt(any())).thenReturn(Flux.just(outage("town")));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(publisher.publishReminder(anyString())).thenReturn(Mono.empty());
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
    }

    @Test
    void should_tell_an_off_frequency_after_two_minutes_with_tauron_as_the_cause() {
        meter(51.0);

        minutesOf(1);
        verify(publisher, never()).publishAlert(anyString());
        assertThat(state.current()).isEqualTo(new FrequencyState.Snapshot(51.0, true));

        minutesOf(0);
        verify(publisher).publishAlert("The grid frequency is 51.00 Hz since 07:05 (49.80 - 50.20 Hz allowed; the "
            + "voltages 232 / 227 / 230 V) - the house is likely fed by a generator of Tauron: a planned outage in the "
            + "town Tue 24 Nov 06:00-18:00. The inverters do not connect to such a grid: no production of the panels, "
            + "no charge of the battery from the grid.");
        assertThat(saved).containsExactly(new SourceAlertEntity(1L, "meter", "frequency", T0, T0.plusMinutes(2), null,
            null));
    }

    @Test
    void should_name_a_generator_or_a_fault_without_an_outage_of_tauron() {
        when(outages.findByClosedAtIsNullAndEndsAtAfterOrderByStartsAt(any())).thenReturn(Flux.empty());
        meter(49.5);

        minutesOf(2);

        verify(publisher).publishAlert(contains(
            " - a generator or a fault of the grid - no outage of Tauron is announced for now."));
    }

    @Test
    void should_name_an_outage_at_the_street_of_the_house() {
        watch.cause(T0).as(StepVerifier::create)
            .expectNext("the house is likely fed by a generator of Tauron: a planned outage in the town "
                + "Tue 24 Nov 06:00-18:00")
            .verifyComplete();
        when(outages.findByClosedAtIsNullAndEndsAtAfterOrderByStartsAt(any())).thenReturn(Flux.just(outage("street")));
        watch.cause(T0).as(StepVerifier::create)
            .expectNext("the house is likely fed by a generator of Tauron: a planned outage at the street of the house "
                + "Tue 24 Nov 06:00-18:00")
            .verifyComplete();
    }

    //one work announced in two rows for other streets of the town (2026-11-24) is told once; another one stays
    @Test
    void should_tell_outages_worded_the_same_once() {
        when(outages.findByClosedAtIsNullAndEndsAtAfterOrderByStartsAt(any()))
            .thenReturn(Flux.just(outage("town"), outage("town"), outage("street")));

        watch.cause(T0).as(StepVerifier::create)
            .expectNext("the house is likely fed by a generator of Tauron: a planned outage in the town "
                + "Tue 24 Nov 06:00-18:00; a planned outage at the street of the house Tue 24 Nov 06:00-18:00")
            .verifyComplete();
    }

    @Test
    void should_never_decide_on_a_stale_meter() {
        when(gridService.grid()).thenReturn(Mono.just(grid(51.0, true)));

        minutesOf(5);

        verify(publisher, never()).publishAlert(anyString());
        verify(outages, never()).findByClosedAtIsNullAndEndsAtAfterOrderByStartsAt(any());
        assertThat(state.current()).isEqualTo(new FrequencyState.Snapshot(null, null));
        assertThat(state.off()).isFalse();
    }

    @Test
    void should_not_read_the_outages_while_the_frequency_is_inside_the_band() {
        meter(50.01);

        minutesOf(3);

        verify(outages, never()).findByClosedAtIsNullAndEndsAtAfterOrderByStartsAt(any());
        assertThat(state.current()).isEqualTo(new FrequencyState.Snapshot(50.01, false));
    }

    @Test
    void should_store_nothing_and_decide_again_when_the_alert_is_not_published() {
        when(publisher.publishAlert(anyString())).thenReturn(Mono.error(new IllegalStateException("broker down")))
            .thenReturn(Mono.empty());
        meter(51.0);

        minutesOf(2);
        assertThat(saved).isEmpty();

        minutesOf(0);
        verify(publisher, times(2)).publishAlert(anyString());
        assertThat(saved).singleElement().satisfies(row -> assertThat(row.openedAt()).isEqualTo(T0));
    }

    @Test
    void should_close_the_episode_with_the_range_seen() {
        meter(51.0);
        minutesOf(2);
        meter(51.04);
        minutesOf(0);
        meter(50.0);

        minutesOf(5);

        verify(publisher).publishInfo("The grid frequency is back: 50.00 Hz - off from 07:05 to 07:09 (4 min), "
            + "51.00 - 51.04 Hz seen.");
        assertThat(saved).singleElement().satisfies(row -> assertThat(row.closedAt()).isEqualTo(T0.plusMinutes(4)));
        assertThat(watch.memory()).isEqualTo(FrequencyRules.Memory.EMPTY);
    }

    @Test
    void should_keep_the_solax_in_the_words_while_fresh() {
        final SolaxReading reading = new SolaxReading("H1", 0, null, null, null, 50.99, 1.0, 0.0, 38.0, 0.0, 4856.8,
            null, null, 1498.0, null, null);
        final FrequencyRules.Reading read = watch.readingOf(grid(51.0, false), new SolaxState(134L, reading,
            T0.minusSeconds(10), true), T0);
        assertThat(read.solax()).isEqualTo(50.99);

        final FrequencyRules.Reading stale = watch.readingOf(grid(51.0, false), new SolaxState(134L, reading,
            T0.minusMinutes(5), true), T0);
        assertThat(stale.solax()).isNull();
    }

    //a told alert the database did not take is held and stored first by the next pass - never told twice
    @Test
    void should_hold_a_told_row_the_database_did_not_take_and_never_tell_it_twice() {
        doReturn(Mono.error(new IllegalStateException("database down")))
            .doAnswer(call -> {
                final SourceAlertEntity row = call.getArgument(0);
                saved.add(new SourceAlertEntity(1L, row.source(), row.kind(), row.openedAt(), row.lastToldAt(),
                    row.closedAt(), row.detail()));
                return Mono.just(row);
            })
            .when(alerts).save(any());
        meter(51.0);

        minutesOf(4);

        verify(publisher, times(1)).publishAlert(anyString());
        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.openedAt()).isEqualTo(T0);
            assertThat(row.lastToldAt()).isEqualTo(T0.plusMinutes(2));
        });
    }

    //a restart during an episode: the memory is empty, the stored row carries it - a reminder an hour after the last
    //notice, the info with the range seen since the restart
    @Test
    void should_take_up_an_open_episode_after_a_restart() {
        saved.add(new SourceAlertEntity(1L, "meter", "frequency", T0.minusHours(3), T0.minusMinutes(60), null, null));
        meter(51.02);

        minutesOf(0);
        verify(publisher).publishReminder("The grid frequency is still off: 51.02 Hz since 04:05 (51.02 - 51.02 Hz "
            + "seen; the voltages 232 / 227 / 230 V) - the house is likely fed by a generator of Tauron: a planned outage "
            + "in the town Tue 24 Nov 06:00-18:00.");

        meter(50.0);
        minutesOf(5);

        verify(publisher).publishInfo("The grid frequency is back: 50.00 Hz - off from 04:05 to 07:06 (3 h 1 min), "
            + "51.02 - 51.02 Hz seen.");
        assertThat(saved).singleElement().satisfies(row -> assertThat(row.closedAt()).isEqualTo(T0.plusMinutes(1)));
    }

    //a pass that hangs is cut off below the delay of 30 s
    @Test
    void should_bound_a_pass_that_hangs() {
        when(gridService.grid()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(watch::check)
            .expectSubscription()
            .expectNoEvent(FrequencyWatch.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }
}

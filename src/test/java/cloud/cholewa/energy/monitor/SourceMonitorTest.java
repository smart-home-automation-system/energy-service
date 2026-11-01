package cloud.cholewa.energy.monitor;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
import cloud.cholewa.energy.meter.GridMeterState;
import cloud.cholewa.energy.meter.GridMeterTracker;
import cloud.cholewa.energy.meter.MeterProperties;
import cloud.cholewa.energy.solax.SolaxSamples;
import cloud.cholewa.energy.solax.SolaxState;
import cloud.cholewa.energy.solax.SolaxTracker;
import cloud.cholewa.shelly.model.EmStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the monitor of the sources (HAS-332): what the trackers say judged every minute, the open episodes read after a start,
//a notification that failed tried again, a row the database did not take stored later, the pass bounded
class SourceMonitorTest {

    private final SolaxSamples.MovableClock clock = new SolaxSamples.MovableClock();
    private final SolaxTracker solaxTracker = mock(SolaxTracker.class);
    private final GridMeterTracker meterTracker = mock(GridMeterTracker.class);
    private final SourceAlertRepository repository = mock(SourceAlertRepository.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final AtomicLong ids = new AtomicLong(1);
    private SourceMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new SourceMonitor(solaxTracker, meterTracker, repository, publisher, SourceRulesTest.AVAILABILITY,
            SourceRulesTest.OVERLOAD, new MeterProperties("shellypro3em-000000000000", "localhost:1",
            Duration.ofMinutes(2)), clock);
        when(repository.findByClosedAtIsNullAndKindIn(SourceMonitor.KINDS)).thenReturn(Flux.empty());
        when(repository.save(any())).thenAnswer(call -> {
            final SourceAlertEntity row = call.getArgument(0);
            return Mono.just(row.id() != null ? row : new SourceAlertEntity(ids.getAndIncrement(), row.source(),
                row.kind(), row.openedAt(), row.lastToldAt(), row.closedAt(), null));
        });
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(publisher.publishReminder(anyString())).thenReturn(Mono.empty());
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
        solax(null, null);
        meter(LocalDateTime.now(clock), true, new EmStatus().aCurrent(5.0).bCurrent(5.0).cCurrent(5.0));
    }

    private void solax(final Boolean reachable, final LocalDateTime readingAt) {
        when(solaxTracker.current()).thenReturn(new SolaxState(134L, null, readingAt, reachable));
    }

    private void meter(final LocalDateTime readingsAt, final Boolean rpcReachable, final EmStatus readings) {
        when(meterTracker.current()).thenReturn(new GridMeterState(readings, readingsAt, null, null, true,
            rpcReachable));
    }

    private void pass() {
        monitor.check().as(StepVerifier::create).verifyComplete();
        clock.moveBy(Duration.ofMinutes(1));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    //DoD: the dongle unplugged for 6 min is one alert and one info
    @Test
    void should_alert_once_of_a_silent_dongle_and_tell_its_return() {
        final LocalDateTime lastReading = now();
        solax(false, lastReading);
        for (int minute = 0; minute < 6; minute++) {
            pass();
        }
        solax(true, now());
        pass();
        pass();

        verify(publisher).publishAlert(startsWith("Not available: the SOLAX dongle does not answer since"));
        verify(publisher).publishInfo(startsWith("Available again: the SOLAX dongle - out from"));
        verify(publisher, never()).publishReminder(anyString());
        assertThat(monitor.current().open()).isEmpty();
        assertThat(monitor.current().unsaved()).isEmpty();
        verify(repository, times(2)).save(any());
    }

    //the broker alone going down is no silence of the meter: the reconciliation still reads it
    @Test
    void should_not_take_the_meter_for_silent_while_it_answers_over_rpc() {
        final LocalDateTime lastMessage = now();
        for (int minute = 0; minute < 10; minute++) {
            meter(minute < 4 ? lastMessage : lastMessage.plusMinutes(4), true, null);
            pass();
        }

        verify(publisher, never()).publishAlert(anyString());
    }

    @Test
    void should_alert_of_a_meter_silent_on_mqtt_and_rpc() {
        meter(now(), false, null);
        for (int minute = 0; minute < 6; minute++) {
            pass();
        }

        verify(publisher).publishAlert(startsWith("Not available: the grid meter is silent since"));
    }

    //a meter never heard since the start counts from the start
    @Test
    void should_count_a_meter_never_heard_from_the_start() {
        meter(null, false, null);
        for (int minute = 0; minute < 5; minute++) {
            pass();
        }
        verify(publisher, never()).publishAlert(anyString());

        pass();

        verify(publisher).publishAlert(startsWith("Not available: the grid meter is silent since"));
    }

    //a failed notification changes nothing and is due again; the opened episode is stored all the same
    @Test
    void should_try_a_failed_alert_again_with_the_next_pass() {
        solax(false, now());
        when(publisher.publishAlert(anyString())).thenReturn(Mono.error(new IllegalStateException("broker down")),
            Mono.empty());
        for (int minute = 0; minute < 7; minute++) {
            pass();
        }

        verify(publisher, times(2)).publishAlert(anyString());
        assertThat(monitor.current().open().get(Source.SOLAX).lastToldAt()).isNotNull();
        assertThat(monitor.current().open().get(Source.SOLAX).id()).isEqualTo(1L);
    }

    //a row the database did not take is stored with a later pass
    @Test
    void should_store_a_row_the_database_did_not_take_later() {
        solax(false, now());
        doReturn(Mono.error(new IllegalStateException("database down"))).when(repository).save(any());
        for (int minute = 0; minute < 6; minute++) {
            pass();
        }
        assertThat(monitor.current().unsaved()).hasSize(1);
        when(repository.save(any())).thenAnswer(call -> {
            final SourceAlertEntity row = call.getArgument(0);
            return Mono.just(new SourceAlertEntity(9L, row.source(), row.kind(), row.openedAt(), row.lastToldAt(),
                row.closedAt(), null));
        });

        pass();

        assertThat(monitor.current().unsaved()).isEmpty();
        assertThat(monitor.current().open().get(Source.SOLAX).id()).isEqualTo(9L);
        verify(publisher).publishAlert(anyString());
    }

    //the episode open before a restart is held: its return is told, the alert never repeated
    @Test
    void should_hold_an_episode_read_after_a_start() {
        when(repository.findByClosedAtIsNullAndKindIn(SourceMonitor.KINDS)).thenReturn(Flux.just(new SourceAlertEntity(3L, "solax", "silent",
            now().minusMinutes(20), now().minusMinutes(14), null, null)));
        solax(false, now().minusMinutes(20));
        pass();
        solax(true, now());
        pass();

        verify(publisher, never()).publishAlert(anyString());
        verify(publisher).publishInfo(startsWith("Available again: the SOLAX dongle"));
        verify(repository).save(new SourceAlertEntity(3L, "solax", "silent", now().minusMinutes(22),
            now().minusMinutes(16), now().minusMinutes(1), null));
    }

    //nothing is decided before the open episodes were read
    @Test
    void should_decide_nothing_before_the_open_episodes_were_read() {
        when(repository.findByClosedAtIsNullAndKindIn(SourceMonitor.KINDS)).thenReturn(Flux.error(new IllegalStateException("database down")));
        solax(false, now().minusMinutes(30));

        pass();

        assertThat(monitor.current().loaded()).isFalse();
        verify(publisher, never()).publishAlert(anyString());
    }

    //the overload from the readings of the meter; a reading older than stale-after counts for nothing
    @Test
    void should_warn_of_an_overloaded_phase_from_fresh_readings_only() {
        final EmStatus overloaded = new EmStatus().aCurrent(5.0).bCurrent(23.6).cCurrent(5.0);
        final LocalDateTime old = now();
        for (int minute = 0; minute < 5; minute++) {
            meter(old, true, overloaded);
            pass();
        }
        verify(publisher, never()).publishReminder(anyString());

        for (int minute = 0; minute < 4; minute++) {
            meter(now(), true, overloaded);
            pass();
        }

        verify(publisher).publishReminder(startsWith("Phase B overloaded since"));
    }

    //a publisher that accepts and never answers is cut off, the pass ends within its bound
    @Test
    void should_end_a_pass_whose_notification_never_completes() {
        when(publisher.publishAlert(anyString())).thenReturn(Mono.never());
        solax(false, now().minusMinutes(10));

        StepVerifier.withVirtualTime(monitor::check)
            .expectSubscription()
            .thenAwait(SourceMonitor.NOTICE_TIMEOUT)
            .verifyComplete();

        assertThat(monitor.current().open().get(Source.SOLAX).lastToldAt()).isNull();
        assertThat(SourceMonitor.NOTICE_TIMEOUT.multipliedBy(6)).isLessThan(SourceMonitor.LIMIT);
    }

    //a reactive @Scheduled method is called once and subscribed to at every run: "now" is read in every pass
    @Test
    void should_judge_again_at_every_run() {
        solax(false, now());
        final Mono<Void> check = monitor.check();
        for (int minute = 0; minute < 6; minute++) {
            check.as(StepVerifier::create).verifyComplete();
            clock.moveBy(Duration.ofMinutes(1));
        }

        verify(publisher).publishAlert(anyString());
    }
}

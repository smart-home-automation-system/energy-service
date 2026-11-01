package cloud.cholewa.energy.outage;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.energy.database.GridOutageEntity;
import cloud.cholewa.energy.database.GridOutageRepository;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.solax.SolaxReading;
import cloud.cholewa.energy.solax.SolaxSamples;
import cloud.cholewa.energy.solax.SolaxState;
import cloud.cholewa.energy.solax.SolaxTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static cloud.cholewa.energy.outage.OutageSamples.eps;
import static cloud.cholewa.energy.outage.OutageSamples.present;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the watch of the grid (HAS-334): the open outage read after a start, the row stored before the notifications go out,
//a failed notification swallowed, the pass bounded
class GridOutageWatchTest {

    private final SolaxSamples.MovableClock clock = new SolaxSamples.MovableClock();
    private final SolaxTracker solaxTracker = mock(SolaxTracker.class);
    private final GridOutageRepository repository = mock(GridOutageRepository.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final GridOutageWatch watch = new GridOutageWatch(solaxTracker, repository, publisher,
        OutageSamples.PROPERTIES, new SolaxProperties(Duration.ofSeconds(15), Duration.ofMinutes(1), 26.5), clock);
    private final AtomicLong ids = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        when(repository.findFirstByEndedAtIsNull()).thenReturn(Mono.empty());
        when(repository.save(any())).thenAnswer(call -> {
            final GridOutageEntity row = call.getArgument(0);
            return Mono.just(row.id() != null ? row : new GridOutageEntity(ids.getAndIncrement(), row.startedAt(),
                row.endedAt(), row.socAtStart(), row.socAtEnd(), row.lowestSoc(), row.lastRemindedAt(),
                row.lowSocAlertedAt()));
        });
        when(publisher.publishAlertSms(anyString())).thenReturn(Mono.empty());
        when(publisher.publishInfoSms(anyString())).thenReturn(Mono.empty());
        when(publisher.publishReminder(anyString())).thenReturn(Mono.empty());
    }

    //the SOLAX read now, then the pass
    private void pass(final SolaxReading reading) {
        when(solaxTracker.current()).thenReturn(new SolaxState(134L, reading, now(), true));
        watch.watch().as(StepVerifier::create).verifyComplete();
        clock.moveBy(Duration.ofSeconds(15));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    @Test
    void should_open_and_close_an_outage_with_both_marked_for_an_sms() {
        pass(eps(64, -2100));
        pass(eps(64, -2100));
        pass(present(60, 0));
        pass(present(60, 0));

        verify(publisher).publishAlertSms(startsWith("Grid lost at "));
        verify(publisher).publishInfoSms(startsWith("Grid back at "));
        assertThat(watch.current().open()).isNull();
        assertThat(watch.current().presentSince()).isNotNull();
    }

    //the row of the outage that lasts is held after a restart: the grid is lost from the start of the service
    @Test
    void should_hold_the_open_outage_read_after_a_start() {
        final GridOutageEntity stored = new GridOutageEntity(7L, LocalDateTime.of(2026, 10, 31, 19, 0), null, 80.0,
            null, 60.0, now(), null);
        when(repository.findFirstByEndedAtIsNull()).thenReturn(Mono.just(stored));

        pass(eps(58, -2000));

        assertThat(watch.current().open().id()).isEqualTo(7L);
        assertThat(watch.current().open().lowestSoc()).isEqualTo(58.0);
        assertThat(GridOutageService.stateOf(watch.current(), false).state()).isEqualTo("lost");
    }

    //nothing is decided before the open outage is known - a failed read is tried again with the next pass
    @Test
    void should_decide_nothing_until_the_open_outage_is_read() {
        when(repository.findFirstByEndedAtIsNull())
            .thenReturn(Mono.error(new IllegalStateException("database down")))
            .thenReturn(Mono.empty());

        pass(eps(64, -2100));
        assertThat(watch.current().loaded()).isFalse();
        pass(eps(64, -2100));
        pass(eps(64, -2100));

        verify(publisher, times(1)).publishAlertSms(anyString());
    }

    //the alert does not wait for the database (managed, outside the house): sent once at the loss, the row it could
    //not store tried again with the next pass and its id taken when stored
    @Test
    void should_alert_without_the_database_and_store_the_row_later() {
        doReturn(Mono.error(new IllegalStateException("database down")))
            .doAnswer(call -> Mono.just(((GridOutageEntity) call.getArgument(0)).withId(9L)))
            .when(repository).save(any());

        pass(eps(64, -2100));
        pass(eps(64, -2100));
        verify(publisher, times(1)).publishAlertSms(anyString());
        assertThat(watch.current().open().id()).isNull();
        assertThat(watch.current().unsaved()).isNotNull();

        pass(eps(64, -2100));
        verify(publisher, times(1)).publishAlertSms(anyString());
        verify(repository, times(2)).save(any());
        assertThat(watch.current().open().id()).isEqualTo(9L);
        assertThat(watch.current().unsaved()).isNull();
    }

    //the outage is stored; the notification is the broker's, and its failure ends the pass like a success
    @Test
    void should_complete_the_pass_when_a_notification_fails() {
        when(publisher.publishAlertSms(anyString())).thenReturn(Mono.error(new IllegalStateException("broker down")));

        pass(eps(64, -2100));
        pass(eps(64, -2100));

        assertThat(watch.current().open()).isNotNull();
    }

    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        when(solaxTracker.current()).thenReturn(new SolaxState(134L, eps(64, -2100), now(), true));
        doReturn(Mono.never()).when(repository).findFirstByEndedAtIsNull();

        StepVerifier.withVirtualTime(watch::watch)
            .expectSubscription()
            .expectNoEvent(GridOutageWatch.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }
}

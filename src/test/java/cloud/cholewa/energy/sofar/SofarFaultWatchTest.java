package cloud.cholewa.energy.sofar;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
import cloud.cholewa.energy.solax.SolaxSamples;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the watch of the faults of the SOFAR (HAS-330): only a fresh reading decides; the table follows the broker - a row only
//once its notification was published
class SofarFaultWatchTest {

    //three minutes after the reading was collected
    private final Clock clock = Clock.fixed(SofarSamples.COLLECTED_AT.plusMinutes(3).atZone(SolaxSamples.HOUSE).toInstant(),
        SolaxSamples.HOUSE);
    private final SofarTracker tracker = mock(SofarTracker.class);
    private final SourceAlertRepository repository = mock(SourceAlertRepository.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final SofarFaultWatch watch = new SofarFaultWatch(tracker, repository, publisher, SofarSamples.PROPERTIES,
        clock);

    @BeforeEach
    void setUp() {
        when(repository.findByClosedAtIsNullAndSourceAndKind("sofar", "alarm")).thenReturn(Flux.empty());
        when(repository.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
    }

    @Test
    void should_alert_of_a_new_fault_and_hold_it() {
        when(tracker.current()).thenReturn(SofarSamples.state(SofarSamples.withFaults(List.of("GridOVP")), true));

        watch.check().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishAlert("SOFAR fault: GridOVP - since 10:19.");
        verify(repository).save(argThat(row -> row.source().equals("sofar") && row.detail().equals("GridOVP")
            && row.closedAt() == null));
    }

    @Test
    void should_hold_nothing_when_the_alert_was_not_published() {
        when(tracker.current()).thenReturn(SofarSamples.state(SofarSamples.withFaults(List.of("GridOVP")), true));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.error(new IllegalStateException("broker down")));

        watch.check().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).save(any());
    }

    @Test
    void should_tell_the_end_of_a_fault_and_close_it() {
        when(tracker.current()).thenReturn(SofarSamples.state(SofarSamples.reading(), true));
        when(repository.findByClosedAtIsNullAndSourceAndKind("sofar", "alarm")).thenReturn(Flux.just(
            new SourceAlertEntity(9L, "sofar", "alarm", LocalDateTime.of(2026, 11, 2, 9, 40),
                LocalDateTime.of(2026, 11, 2, 9, 40), null, "GridOVP")));

        watch.check().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishInfo("SOFAR fault over: GridOVP - from 09:40 to 10:22 (42 min).");
        verify(repository).save(argThat(row -> row.id() == 9L && row.closedAt() != null));
    }

    //a stale SOFAR - a failed read, or a reading collected too long ago - neither opens nor ends a fault
    @Test
    void should_decide_nothing_while_the_sofar_is_stale() {
        when(tracker.current()).thenReturn(SofarSamples.state(SofarSamples.withFaults(List.of("GridOVP")), false));

        watch.check().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).findByClosedAtIsNullAndSourceAndKind(any(), any());
        verify(publisher, never()).publishAlert(anyString());
    }

    @Test
    void should_decide_nothing_before_the_first_reading() {
        when(tracker.current()).thenReturn(new SofarState(null, null, null, null));

        watch.check().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).findByClosedAtIsNullAndSourceAndKind(any(), any());
    }

    //it runs inside the read of the SOFAR: a database that cannot be read is a WARN, never an error of the read
    @Test
    void should_never_error_when_the_open_faults_cannot_be_read() {
        when(tracker.current()).thenReturn(SofarSamples.state(SofarSamples.withFaults(List.of("GridOVP")), true));
        doReturn(Flux.error(new IllegalStateException("database down"))).when(repository)
            .findByClosedAtIsNullAndSourceAndKind(any(), any());

        watch.check().as(StepVerifier::create).verifyComplete();

        verify(publisher, never()).publishAlert(anyString());
    }
}

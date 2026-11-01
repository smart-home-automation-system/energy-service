package cloud.cholewa.energy.solaxcloud;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
import cloud.cholewa.energy.solax.SolaxSamples;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the watch of the alarms of the SOLAX (HAS-329): the table follows the broker - a row only once its notification was
//published; a read of the cloud that failed decides nothing
class SolaxAlarmWatchTest {

    private final SolaxCloudClient client = mock(SolaxCloudClient.class);
    private final SourceAlertRepository repository = mock(SourceAlertRepository.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final SolaxAlarmWatch watch = new SolaxAlarmWatch(client, repository, publisher,
        new SolaxSamples.MovableClock());

    private static final CloudAlarm ONGOING = new CloudAlarm("Bat Power Low", "17", "1", 2, 1, "2026-10-27 20:30:00",
        null, SolaxSamples.SERIAL);

    @BeforeEach
    void setUp() {
        when(repository.findByClosedAtIsNullAndKindIn(List.of("alarm"))).thenReturn(Flux.empty());
        when(repository.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
    }

    @Test
    void should_alert_of_a_new_alarm_and_hold_it() {
        when(client.alarms(true)).thenReturn(Mono.just(new CloudAlarm.Page(1, 1, List.of(ONGOING))));

        watch.check().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishAlert("SOLAX alarm: Bat Power Low (error 17, type 1, level 2) since 20:30.");
        verify(repository).save(argThat(row -> row.id() == null && row.closedAt() == null
            && "17|2026-10-27 20:30:00|Bat Power Low".equals(row.detail())));
    }

    @Test
    void should_hold_nothing_when_the_alert_was_not_published() {
        when(client.alarms(true)).thenReturn(Mono.just(new CloudAlarm.Page(1, 1, List.of(ONGOING))));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.error(new IllegalStateException("broker down")));

        watch.check().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).save(any());
    }

    @Test
    void should_tell_the_end_of_an_alarm_and_close_it() {
        when(client.alarms(true)).thenReturn(Mono.just(new CloudAlarm.Page(0, 0, List.of())));
        when(repository.findByClosedAtIsNullAndKindIn(List.of("alarm"))).thenReturn(Flux.just(new SourceAlertEntity(5L,
            "solax", "alarm", LocalDateTime.of(2026, 10, 27, 20, 30), LocalDateTime.of(2026, 10, 27, 20, 30), null,
            "17|2026-10-27 20:30:00|Bat Power Low")));

        watch.check().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishInfo(startsWith("SOLAX alarm over: Bat Power Low (error 17) - from 20:30 to 20:49"));
        verify(repository).save(argThat(row -> row.id() == 5L && row.closedAt() != null));
    }

    @Test
    void should_decide_nothing_when_the_cloud_cannot_be_read() {
        when(client.alarms(true)).thenReturn(Mono.error(new SolaxCloudException("The SolaX cloud answered code 10500")));

        watch.check().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).findByClosedAtIsNullAndKindIn(any());
        verify(publisher, never()).publishAlert(anyString());
    }
}

package cloud.cholewa.energy.outage;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.energy.database.GridOutageEntity;
import cloud.cholewa.energy.database.GridOutageRepository;
import cloud.cholewa.energy.database.MeterUptimeEntity;
import cloud.cholewa.energy.database.MeterUptimeRepository;
import cloud.cholewa.energy.meter.MeterProperties;
import cloud.cholewa.energy.solax.SolaxSamples;
import cloud.cholewa.shelly.http.ShellyCallException;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the outages seen after the fact from the uptime of the grid meter (HAS-505): stored and told once, never over an
//outage the live watch holds or stored, the row of the meter written only after the outage was, the pass bounded
class MeterRestartWatchTest {

    private static final String ADDRESS = "192.0.2.10";

    private final SolaxSamples.MovableClock clock = new SolaxSamples.MovableClock();
    private final ShellyRpcClient rpcClient = mock(ShellyRpcClient.class);
    private final MeterUptimeRepository uptimes = mock(MeterUptimeRepository.class);
    private final GridOutageRepository outages = mock(GridOutageRepository.class);
    private final GridOutageWatch liveWatch = mock(GridOutageWatch.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final MeterRestartWatch watch = new MeterRestartWatch(
        new MeterProperties("shellypro3em-000000000000", ADDRESS, Duration.ofMinutes(2)), rpcClient, uptimes, outages,
        liveWatch, publisher, clock);

    @BeforeEach
    void setUp() {
        when(uptimes.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(outages.save(any())).thenAnswer(call -> Mono.just(((GridOutageEntity) call.getArgument(0)).withId(9L)));
        when(outages.existsOverlapping(any(), any())).thenReturn(Mono.just(false));
        when(liveWatch.current()).thenReturn(GridWatch.loaded(null));
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private void stored(final LocalDateTime seenAt, final LocalDateTime bootedAt) {
        when(uptimes.findById(MeterUptimeEntity.ROW_ID)).thenReturn(Mono.just(new MeterUptimeEntity(1, seenAt, bootedAt)));
    }

    private void answers(final long uptime, final Integer resetReason) {
        final Map<String, Object> sys = resetReason == null
            ? Map.of("uptime", uptime)
            : Map.of("uptime", uptime, "reset_reason", resetReason);
        when(rpcClient.status(ADDRESS)).thenReturn(Mono.just(Map.of("sys", sys)));
    }

    //DoD: the service down with the house - the meter last seen at 06:57, started again 60 s after it; the same Mono of
    //the scheduler subscribed a minute later reads the time anew
    @Test
    void should_store_an_outage_between_the_meter_last_seen_and_its_start_and_tell_it_once() {
        final LocalDateTime seen = now().minusMinutes(10);
        stored(seen, seen.minusDays(14));
        answers(Duration.ofMinutes(9).toSeconds(), 1);
        final Mono<Void> scheduled = watch.check();

        scheduled.as(StepVerifier::create).verifyComplete();

        final LocalDateTime booted = now().minusMinutes(9);
        verify(outages).existsOverlapping(seen, booted);
        verify(outages).save(GridOutageEntity.inferred(seen, booted, now()));
        verify(publisher).publishInfo("The grid was lost while the house was down - between "
            + "20:39 and 20:40 (1 min at most) the grid meter lost its power; stored after the fact.");
        verify(uptimes).save(new MeterUptimeEntity(1, now(), booted));

        //the next minute the start lies before the time it was seen: nothing more
        clock.moveBy(Duration.ofMinutes(1));
        answers(Duration.ofMinutes(10).toSeconds(), 1);
        scheduled.as(StepVerifier::create).verifyComplete();

        verify(outages).save(any());
        verify(uptimes).save(new MeterUptimeEntity(1, now(), booted));
        verify(publisher).publishInfo(anyString());
        verify(uptimes, times(2)).save(any());
    }

    //DoD: an outage stored live is never stored again after the fact
    @Test
    void should_store_nothing_over_an_outage_stored_or_held_live() {
        final LocalDateTime seen = now().minusMinutes(10);
        stored(seen, seen.minusDays(14));
        answers(Duration.ofMinutes(5).toSeconds(), 1);
        when(outages.existsOverlapping(any(), any())).thenReturn(Mono.just(true));

        watch.check().as(StepVerifier::create).verifyComplete();

        when(outages.existsOverlapping(any(), any())).thenReturn(Mono.just(false));
        when(liveWatch.current()).thenReturn(GridWatch.loaded(GridOutageEntity.opened(seen.plusMinutes(1), 40.0, seen)));
        clock.moveBy(Duration.ofMinutes(1));
        answers(30, 1);
        watch.check().as(StepVerifier::create).verifyComplete();

        verify(outages, never()).save(any());
        verify(publisher, never()).publishInfo(anyString());
    }

    @Test
    void should_store_nothing_for_a_restart_of_the_meter_or_one_without_a_reset_reason() {
        final LocalDateTime seen = now().minusMinutes(10);
        stored(seen, seen.minusDays(14));
        answers(60, 3);
        watch.check().as(StepVerifier::create).verifyComplete();

        answers(60, null);
        watch.check().as(StepVerifier::create).verifyComplete();

        verify(outages, never()).save(any());
        verify(publisher, never()).publishInfo(anyString());
    }

    //the row is read once after a start, then held; the first answer only records the meter
    @Test
    void should_record_the_first_answer_and_read_the_row_once() {
        stored(null, null);
        answers(49940, 1);

        watch.check().as(StepVerifier::create).verifyComplete();
        clock.moveBy(Duration.ofMinutes(1));
        watch.check().as(StepVerifier::create).verifyComplete();

        verify(uptimes).findById(MeterUptimeEntity.ROW_ID);
        verify(uptimes).save(new MeterUptimeEntity(1, now(), now().minusSeconds(49940)));
        verify(outages, never()).save(any());
    }

    //the meter off: nothing decided, nothing written
    @Test
    void should_write_nothing_while_the_meter_does_not_answer() {
        stored(now().minusMinutes(10), now().minusDays(14));
        when(rpcClient.status(ADDRESS)).thenReturn(Mono.error(new ShellyCallException("connection refused")));

        watch.check().as(StepVerifier::create).verifyComplete();
        when(rpcClient.status(ADDRESS)).thenReturn(Mono.just(Map.of("sys", Map.of("ram_free", 1))));
        watch.check().as(StepVerifier::create).verifyComplete();

        verify(uptimes, never()).save(any());
        verify(outages, never()).existsOverlapping(any(), any());
    }

    //a store that failed keeps the row of the meter as it was, so the next pass decides the outage again
    @Test
    void should_keep_the_row_of_the_meter_while_the_outage_is_not_stored() {
        final LocalDateTime seen = now().minusMinutes(10);
        stored(seen, seen.minusDays(14));
        answers(Duration.ofMinutes(9).toSeconds(), 1);
        doReturn(Mono.error(new IllegalStateException("database down"))).when(outages).save(any());

        watch.check().as(StepVerifier::create).verifyComplete();
        verify(uptimes, never()).save(any());

        doAnswer(call -> Mono.just(((GridOutageEntity) call.getArgument(0)).withId(9L))).when(outages).save(any());
        clock.moveBy(Duration.ofMinutes(1));
        answers(Duration.ofMinutes(10).toSeconds(), 1);
        watch.check().as(StepVerifier::create).verifyComplete();

        verify(outages, times(2)).save(any());
        verify(publisher).publishInfo(anyString());
    }

    //a failed notification is swallowed: the outage stays stored and the meter recorded
    @Test
    void should_swallow_a_notification_that_was_not_published() {
        final LocalDateTime seen = now().minusMinutes(10);
        stored(seen, seen.minusDays(14));
        answers(Duration.ofMinutes(9).toSeconds(), 1);
        when(publisher.publishInfo(anyString())).thenReturn(Mono.error(new IllegalStateException("no broker")));

        watch.check().as(StepVerifier::create).verifyComplete();

        verify(outages).save(any());
        verify(uptimes).save(any());
    }

    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        stored(now().minusMinutes(10), now().minusDays(14));
        doReturn(Mono.never()).when(rpcClient).status(ADDRESS);

        StepVerifier.withVirtualTime(watch::check)
            .expectSubscription()
            .expectNoEvent(MeterRestartWatch.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }
}

package cloud.cholewa.energy.meter;

import cloud.cholewa.shelly.http.ShellyCallException;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;

import static cloud.cholewa.energy.meter.MeterSamples.METER;
import static cloud.cholewa.energy.meter.MeterSamples.counters;
import static cloud.cholewa.energy.meter.MeterSamples.readings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeterReconciliationTest {

    private static final String ADDRESS = "192.0.2.10";

    private final MeterSamples.MovableClock clock = new MeterSamples.MovableClock();
    private final GridMeterTracker tracker = new GridMeterTracker(clock);
    private final ShellyRpcClient rpcClient = mock(ShellyRpcClient.class);
    private final MeterReconciliation reconciliation = new MeterReconciliation(
        new MeterProperties(METER, ADDRESS, Duration.ofMinutes(2)), rpcClient, tracker);

    @Test
    void should_read_the_readings_and_the_counters_of_the_meter() {
        when(rpcClient.emStatus(ADDRESS, 0)).thenReturn(Mono.just(readings(-113.5)));
        when(rpcClient.emDataStatus(ADDRESS, 0)).thenReturn(Mono.just(counters(891011.75, 2158851.5)));

        reconciliation.reconcile().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().readings().getTotalActPower()).isEqualTo(-113.5);
        assertThat(tracker.current().counters().getTotalAct()).isEqualTo(891011.75);
        verify(rpcClient).emStatus(ADDRESS, 0);
        verify(rpcClient).emDataStatus(ADDRESS, 0);
    }

    //DoD: after a gap of the broker the RPC values replace the MQTT state that stopped
    @Test
    void should_replace_a_stale_mqtt_state_with_what_the_meter_answers() {
        tracker.readings(readings(-113.5));
        clock.moveBy(Duration.ofMinutes(4));
        when(rpcClient.emStatus(ADDRESS, 0)).thenReturn(Mono.just(readings(840.0)));
        when(rpcClient.emDataStatus(ADDRESS, 0)).thenReturn(Mono.just(counters(891100.0, 2158851.5)));

        reconciliation.reconcile().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().readings().getTotalActPower()).isEqualTo(840.0);
        assertThat(tracker.current().readingsAt()).isEqualTo(LocalDateTime.of(2026, 10, 27, 11, 4));
    }

    //each call has its own error handling: the counters are read when the readings failed, and the other way
    @Test
    void should_read_the_counters_when_the_readings_failed() {
        when(rpcClient.emStatus(ADDRESS, 0)).thenReturn(Mono.error(new ShellyCallException("refused")));
        when(rpcClient.emDataStatus(ADDRESS, 0)).thenReturn(Mono.just(counters(891011.75, 2158851.5)));

        reconciliation.reconcile().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().readings()).isNull();
        assertThat(tracker.current().counters()).isNotNull();
    }

    @Test
    void should_keep_the_readings_when_the_counters_failed() {
        when(rpcClient.emStatus(ADDRESS, 0)).thenReturn(Mono.just(readings(-113.5)));
        when(rpcClient.emDataStatus(ADDRESS, 0)).thenReturn(Mono.error(new ShellyCallException("refused")));

        reconciliation.reconcile().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().readings()).isNotNull();
        assertThat(tracker.current().counters()).isNull();
    }

    //a failed read says nothing of the broker: the meter that is on it stays on it
    @Test
    void should_leave_the_online_state_to_mqtt_when_a_read_fails() {
        tracker.online(false);
        when(rpcClient.emStatus(ADDRESS, 0)).thenReturn(Mono.error(new ShellyCallException("refused")));
        when(rpcClient.emDataStatus(ADDRESS, 0)).thenReturn(Mono.error(new ShellyCallException("refused")));

        reconciliation.reconcile().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().online()).isFalse();
    }

    //a meter that accepts the call and never answers ends the pass at its bound, below the five minutes between
    //passes (HAS-300)
    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        when(rpcClient.emStatus(ADDRESS, 0)).thenReturn(Mono.never());
        when(rpcClient.emDataStatus(ADDRESS, 0)).thenReturn(Mono.just(counters(1.0, 2.0)));

        StepVerifier.withVirtualTime(reconciliation::reconcile)
            .expectSubscription()
            .expectNoEvent(MeterReconciliation.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }

    //a reactive @Scheduled method is called once and subscribed to at every run: the second run reads again
    @Test
    void should_read_the_meter_again_at_every_run() {
        when(rpcClient.emStatus(ADDRESS, 0)).thenReturn(Mono.just(readings(-113.5)), Mono.just(readings(300.0)));
        when(rpcClient.emDataStatus(ADDRESS, 0)).thenReturn(Mono.just(counters(1.0, 2.0)));
        final Mono<Void> pass = reconciliation.reconcile();

        pass.as(StepVerifier::create).verifyComplete();
        clock.moveBy(Duration.ofMinutes(5));
        pass.as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().readings().getTotalActPower()).isEqualTo(300.0);
        assertThat(tracker.current().readingsAt()).isEqualTo(LocalDateTime.of(2026, 10, 27, 11, 5));
    }
}

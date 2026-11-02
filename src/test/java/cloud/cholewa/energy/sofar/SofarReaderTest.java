package cloud.cholewa.energy.sofar;

import cloud.cholewa.energy.registry.InverterRegistry;
import cloud.cholewa.energy.solax.SolaxSamples;
import cloud.cholewa.home.model.InverterBrand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the read of the SOFAR from the Solarman cloud (HAS-330): the inverter of the registry by its serial, reachable while
//the reading is fresh, the last reading kept on a failure
class SofarReaderTest {

    private final InverterRegistry registry = mock(InverterRegistry.class);
    private final SolarmanClient client = mock(SolarmanClient.class);
    private final SofarTracker tracker = new SofarTracker();
    private final SofarFaultWatch faultWatch = mock(SofarFaultWatch.class);

    @BeforeEach
    void setUp() {
        when(faultWatch.check()).thenReturn(Mono.empty());
    }

    private SofarReader reader(final LocalDateTime now) {
        return new SofarReader(registry, client, tracker, faultWatch, SofarSamples.PROPERTIES,
            Clock.fixed(now.atZone(SolaxSamples.HOUSE).toInstant(), SolaxSamples.HOUSE));
    }

    @Test
    void should_read_the_sofar_of_the_registry() {
        when(registry.of(InverterBrand.SOFAR)).thenReturn(Optional.of(SolaxSamples.sofar()));
        when(client.currentData(SofarSamples.SERIAL)).thenReturn(Mono.just(SofarSamples.answer()));
        final LocalDateTime now = SofarSamples.COLLECTED_AT.plusMinutes(2);

        reader(now).read().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current()).isEqualTo(new SofarState(135L, SofarSamples.reading(), now, true));
        verify(faultWatch).check();
    }

    //a failed read decides no fault
    @Test
    void should_decide_no_fault_after_a_failed_read() {
        when(registry.of(InverterBrand.SOFAR)).thenReturn(Optional.of(SolaxSamples.sofar()));
        when(client.currentData(SofarSamples.SERIAL))
            .thenReturn(Mono.error(new SolarmanException("The Solarman cloud answered code 2101016")));

        reader(SofarSamples.COLLECTED_AT).read().as(StepVerifier::create).verifyComplete();

        verify(faultWatch, never()).check();
    }

    //a fault notice cut off by the bound of the pass does not make the read a failed one
    @Test
    void should_keep_a_good_read_reachable_when_the_fault_watch_is_cut_off() {
        when(registry.of(InverterBrand.SOFAR)).thenReturn(Optional.of(SolaxSamples.sofar()));
        when(client.currentData(SofarSamples.SERIAL)).thenReturn(Mono.just(SofarSamples.answer()));
        when(faultWatch.check()).thenReturn(Mono.never());
        final SofarReader reader = reader(SofarSamples.COLLECTED_AT.plusMinutes(2));

        StepVerifier.withVirtualTime(reader::read)
            .expectSubscription()
            .thenAwait(SofarReader.LIMIT)
            .verifyComplete();

        assertThat(tracker.current().reachable()).isTrue();
    }

    //the cloud answers the last reading of a logger that is off: no fresh reading, so not reachable
    @Test
    void should_not_call_a_reading_collected_long_ago_reachable() {
        when(registry.of(InverterBrand.SOFAR)).thenReturn(Optional.of(SolaxSamples.sofar()));
        when(client.currentData(SofarSamples.SERIAL)).thenReturn(Mono.just(SofarSamples.answer()));

        reader(SofarSamples.COLLECTED_AT.plusMinutes(16)).read().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().reachable()).isFalse();
        assertThat(tracker.current().reading()).isEqualTo(SofarSamples.reading());
    }

    @Test
    void should_keep_the_last_reading_when_a_read_fails() {
        when(registry.of(InverterBrand.SOFAR)).thenReturn(Optional.of(SolaxSamples.sofar()));
        when(client.currentData(SofarSamples.SERIAL)).thenReturn(Mono.just(SofarSamples.answer()),
            Mono.error(new SolarmanException("The Solarman cloud answered code 2101016")));
        final SofarReader reader = reader(SofarSamples.COLLECTED_AT.plusMinutes(2));
        final Mono<Void> read = reader.read();

        read.as(StepVerifier::create).verifyComplete();
        read.as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().reachable()).isFalse();
        assertThat(tracker.current().reading()).isEqualTo(SofarSamples.reading());
    }

    //an answer of another device is a failed read
    @Test
    void should_refuse_an_answer_of_another_device() {
        when(registry.of(InverterBrand.SOFAR)).thenReturn(Optional.of(SolaxSamples.sofar()));
        when(client.currentData(SofarSamples.SERIAL)).thenReturn(Mono.just(SolaxSamples.JSON.readTree(
            SofarSamples.sample().replace("\"deviceSn\":\"SH1234567890ABCDEFGH\"", "\"deviceSn\":\"SH0\""))));

        reader(SofarSamples.COLLECTED_AT).read().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().reachable()).isFalse();
        assertThat(tracker.current().reading()).isNull();
    }

    @Test
    void should_read_nothing_without_a_sofar_in_the_registry() {
        when(registry.of(InverterBrand.SOFAR)).thenReturn(Optional.empty());

        reader(SofarSamples.COLLECTED_AT).read().as(StepVerifier::create).verifyComplete();

        verify(client, never()).currentData(anyString());
        assertThat(tracker.current().reachable()).isNull();
    }

    //a SOFAR gone from a registry that was read: its last reading kept, no longer reachable; a good read stays reachable
    @Test
    void should_mark_a_sofar_gone_from_the_registry_unreachable() {
        when(registry.of(InverterBrand.SOFAR)).thenReturn(Optional.of(SolaxSamples.sofar()), Optional.empty());
        when(registry.current()).thenReturn(Optional.of(List.of(SolaxSamples.solax("192.0.2.158"))));
        when(client.currentData(SofarSamples.SERIAL)).thenReturn(Mono.just(SofarSamples.answer()));
        final Mono<Void> read = reader(SofarSamples.COLLECTED_AT.plusMinutes(2)).read();

        read.as(StepVerifier::create).verifyComplete();
        assertThat(tracker.current().reachable()).isTrue();
        read.as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().reachable()).isFalse();
        assertThat(tracker.current().reading()).isEqualTo(SofarSamples.reading());
    }

    @Test
    void should_end_a_read_that_never_completes_at_its_bound() {
        when(registry.of(InverterBrand.SOFAR)).thenReturn(Optional.of(SolaxSamples.sofar()));
        doReturn(Mono.never()).when(client).currentData(anyString());
        final SofarReader reader = reader(SofarSamples.COLLECTED_AT);

        StepVerifier.withVirtualTime(reader::read)
            .expectSubscription()
            .expectNoEvent(SofarReader.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();

        //a read cut off is a failed read
        assertThat(tracker.current().reachable()).isFalse();
    }
}

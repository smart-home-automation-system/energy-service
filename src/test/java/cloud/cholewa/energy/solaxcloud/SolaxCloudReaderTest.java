package cloud.cholewa.energy.solaxcloud;

import cloud.cholewa.energy.registry.InverterRegistry;
import cloud.cholewa.energy.solax.SolaxSamples;
import cloud.cholewa.home.model.InverterBrand;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the read of the SolaX cloud (HAS-329): the inverter and its battery of the registry, the state kept on a failure
class SolaxCloudReaderTest {

    private final SolaxSamples.MovableClock clock = new SolaxSamples.MovableClock();
    private final InverterRegistry registry = mock(InverterRegistry.class);
    private final SolaxCloudClient client = mock(SolaxCloudClient.class);
    private final SolaxCloudTracker tracker = new SolaxCloudTracker();
    private final SolaxCloudReader reader = new SolaxCloudReader(registry, client, tracker, clock);

    @Test
    void should_read_the_inverter_and_its_battery_of_the_registry() {
        when(registry.of(InverterBrand.SOLAX)).thenReturn(Optional.of(SolaxSamples.solax("192.0.2.158")));
        when(client.inverter(SolaxSamples.SERIAL)).thenReturn(Mono.just(CloudSamples.inverter()));
        when(client.battery(SolaxSamples.SERIAL)).thenReturn(Mono.just(CloudSamples.battery()));

        reader.read().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current()).isEqualTo(new SolaxCloudState(CloudSamples.inverter(), CloudSamples.battery(),
            LocalDateTime.now(clock), true));
    }

    //a failed read keeps the last answers and marks the cloud unreachable
    @Test
    void should_keep_the_last_answers_when_a_read_fails() {
        when(registry.of(InverterBrand.SOLAX)).thenReturn(Optional.of(SolaxSamples.solax("192.0.2.158")));
        when(client.inverter(SolaxSamples.SERIAL)).thenReturn(Mono.just(CloudSamples.inverter()),
            Mono.error(new SolaxCloudException("The SolaX cloud answered code 10500")));
        when(client.battery(SolaxSamples.SERIAL)).thenReturn(Mono.just(CloudSamples.battery()));
        final Mono<Void> read = reader.read();

        read.as(StepVerifier::create).verifyComplete();
        read.as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().reachable()).isFalse();
        assertThat(tracker.current().inverter()).isEqualTo(CloudSamples.inverter());
    }

    @Test
    void should_read_nothing_without_a_solax_in_the_registry() {
        when(registry.of(InverterBrand.SOLAX)).thenReturn(Optional.empty());

        reader.read().as(StepVerifier::create).verifyComplete();

        verify(client, never()).inverter(anyString());
        assertThat(tracker.current().reachable()).isNull();
    }
}

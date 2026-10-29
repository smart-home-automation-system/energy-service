package cloud.cholewa.energy.registry;

import cloud.cholewa.home.model.InverterBinding;
import cloud.cholewa.home.model.InverterBrand;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartDevice;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InverterRegistryTest {

    private final DeviceRegistryClient client = mock(DeviceRegistryClient.class);
    private final InverterRegistry registry = new InverterRegistry(client);

    @Test
    void should_know_nothing_before_the_first_read() {
        assertThat(registry.current()).isEmpty();
        assertThat(registry.of(InverterBrand.SOLAX)).isEmpty();
    }

    @Test
    void should_know_the_inverters_of_the_registry_by_brand() {
        when(client.getInverters()).thenReturn(Mono.just(List.of(solax(), sofar())));

        registry.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(registry.of(InverterBrand.SOLAX)).contains(new Inverter(134, "SOLAX inverter", RoomName.GARAGE,
            InverterBrand.SOLAX, "H1234567890ABC", "192.0.2.158", "SABCDEFGHI", null));
        assertThat(registry.of(InverterBrand.SOFAR)).hasValueSatisfying(sofar -> {
            assertThat(sofar.id()).isEqualTo(135);
            assertThat(sofar.loggerSerial()).isEqualTo(1234567890L);
        });
    }

    //an empty registry is known: no inverter, not "not known yet"
    @Test
    void should_know_a_registry_without_inverters() {
        when(client.getInverters()).thenReturn(Mono.just(List.of()));

        registry.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(registry.current()).contains(List.of());
    }

    //a failed read keeps what was read before
    @Test
    void should_keep_the_inverters_when_a_read_fails() {
        when(client.getInverters()).thenReturn(Mono.just(List.of(solax())),
            Mono.error(new RegistryCallException("database-service answered 500")));

        registry.refresh().as(StepVerifier::create).verifyComplete();
        registry.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(registry.of(InverterBrand.SOLAX)).isPresent();
    }

    @Test
    void should_stay_not_known_when_the_first_read_fails() {
        when(client.getInverters()).thenReturn(Mono.error(new RegistryCallException("database-service answered 500")));

        registry.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(registry.current()).isEmpty();
    }

    //a device without its binding is none the service understands: the others are kept
    @Test
    void should_leave_out_a_device_without_an_inverter_binding() {
        when(client.getInverters()).thenReturn(Mono.just(List.of(new SmartDevice(77L).name("Odd"), sofar())));

        registry.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(registry.current()).hasValueSatisfying(known ->
            assertThat(known).extracting(Inverter::id).containsExactly(135L));
    }

    @Test
    void should_end_a_read_that_never_completes_at_its_bound() {
        when(client.getInverters()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(registry::refresh)
            .expectSubscription()
            .expectNoEvent(InverterRegistry.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }

    private static SmartDevice solax() {
        return new SmartDevice(134L).name("SOLAX inverter").room(RoomName.GARAGE)
            .inverter(new InverterBinding().brand(InverterBrand.SOLAX).serialNumber("H1234567890ABC")
                .address("192.0.2.158").registrationNumber("SABCDEFGHI"));
    }

    private static SmartDevice sofar() {
        return new SmartDevice(135L).name("SOFAR inverter").room(RoomName.BOILER)
            .inverter(new InverterBinding().brand(InverterBrand.SOFAR).serialNumber("SH1234567890ABCDEFGH")
                .address("192.0.2.204").loggerSerial(1234567890L));
    }
}

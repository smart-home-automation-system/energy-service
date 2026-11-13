package cloud.cholewa.energy.inverter;

import cloud.cholewa.energy.api.model.InverterReply;
import cloud.cholewa.energy.api.model.PhaseReply;
import cloud.cholewa.energy.api.model.PvReply;
import cloud.cholewa.energy.error.EnergyError;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.registry.Inverter;
import cloud.cholewa.energy.registry.InverterRegistry;
import cloud.cholewa.energy.solax.RunMode;
import cloud.cholewa.energy.sofar.SofarSamples;
import cloud.cholewa.energy.sofar.SofarState;
import cloud.cholewa.energy.sofar.SofarTracker;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.solax.SolaxSamples;
import cloud.cholewa.energy.solax.SolaxState;
import cloud.cholewa.energy.solax.SolaxTracker;
import cloud.cholewa.energy.solaxcloud.CloudSamples;
import cloud.cholewa.energy.solaxcloud.SolaxCloudState;
import cloud.cholewa.energy.solaxcloud.SolaxCloudTracker;
import cloud.cholewa.home.model.InverterBrand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static cloud.cholewa.energy.solax.SolaxSamples.EVENING;
import static cloud.cholewa.energy.solax.SolaxSamples.ID;
import static cloud.cholewa.energy.solax.SolaxSamples.reading;
import static cloud.cholewa.energy.solax.SolaxSamples.sofar;
import static cloud.cholewa.energy.solax.SolaxSamples.solax;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InverterServiceTest {

    private static final LocalDateTime READ_AT = LocalDateTime.of(2026, 10, 27, 20, 49);

    private final SolaxSamples.MovableClock clock = new SolaxSamples.MovableClock();
    private final InverterRegistry registry = mock(InverterRegistry.class);
    private final SolaxTracker tracker = mock(SolaxTracker.class);
    private final SolaxCloudTracker cloudTracker = mock(SolaxCloudTracker.class);
    private final SofarTracker sofarTracker = mock(SofarTracker.class);
    private final InverterService service = new InverterService(registry, tracker,
        new SolaxProperties(Duration.ofSeconds(15), Duration.ofMinutes(1), 26.5), cloudTracker,
        CloudSamples.PROPERTIES, sofarTracker, SofarSamples.PROPERTIES, clock);

    @BeforeEach
    void setUp() {
        when(registry.current()).thenReturn(Optional.of(List.of(solax("192.0.2.158"), sofar())));
        when(tracker.current()).thenReturn(new SolaxState(ID, reading(EVENING), READ_AT, true));
        when(cloudTracker.current()).thenReturn(new SolaxCloudState(null, null, null, null));
        when(sofarTracker.current()).thenReturn(new SofarState(null, null, null, null));
    }

    //the SOLAX with its reading, the SOFAR with its registry entry before its first reading
    @Test
    void should_answer_every_inverter_of_the_registry() {
        service.inverters().as(StepVerifier::create)
            .expectNext(List.of(solaxReply(false), new InverterReply("sofar", 135L, "SOFAR inverter", "boiler",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, true, null)))
            .verifyComplete();
    }

    @Test
    void should_answer_one_inverter_by_its_brand() {
        service.inverter("solax").as(StepVerifier::create)
            .expectNext(solaxReply(false))
            .verifyComplete();
    }

    //older than energy.solax.stale-after: the values are served, marked stale
    @Test
    void should_serve_an_old_reading_as_stale() {
        clock.moveBy(Duration.ofSeconds(61));

        service.inverter("solax").as(StepVerifier::create)
            .expectNext(solaxReply(true))
            .verifyComplete();
    }

    @Test
    void should_serve_the_reading_of_a_failed_read_as_stale() {
        when(tracker.current()).thenReturn(new SolaxState(ID, reading(EVENING), READ_AT, false));

        service.inverter("solax").as(StepVerifier::create)
            .expectNext(solaxReply(true))
            .verifyComplete();
    }

    //after a start, before the first reading: the registry entry and stale
    @Test
    void should_answer_the_registry_entry_before_the_first_reading() {
        when(tracker.current()).thenReturn(new SolaxState(null, null, null, null));

        service.inverter("solax").as(StepVerifier::create)
            .expectNext(new InverterReply("solax", ID, "SOLAX inverter", "garage", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, true, null))
            .verifyComplete();
    }

    @Test
    void should_refuse_an_inverter_not_known() {
        service.inverter("fronius").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOfSatisfying(EnergyException.class, refused -> {
                    assertThat(refused.getError()).isEqualTo(EnergyError.NOT_FOUND_INVERTER);
                    assertThat(refused.getMessage()).isEqualTo("No inverter fronius is registered");
                }))
            .verify();
    }

    //a brand the registry has no inverter of
    @Test
    void should_refuse_an_inverter_not_registered() {
        when(registry.current()).thenReturn(Optional.of(List.of(solax("192.0.2.158"))));

        service.inverter("sofar").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((EnergyException) failure).getError())
                .isEqualTo(EnergyError.NOT_FOUND_INVERTER))
            .verify();
    }

    @Test
    void should_refuse_while_the_registry_is_not_known() {
        when(registry.current()).thenReturn(Optional.empty());

        service.inverters().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((EnergyException) failure).getError())
                .isEqualTo(EnergyError.REGISTRY_UNAVAILABLE))
            .verify();
        service.inverter("solax").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((EnergyException) failure).getError())
                .isEqualTo(EnergyError.REGISTRY_UNAVAILABLE))
            .verify();
    }

    //HAS-330: the SOFAR with its reading from the Solarman cloud - its state and faults in words, the time the logger
    //collected it, no run mode, grid power or power factor
    @Test
    void should_answer_the_sofar_with_its_reading_from_the_solarman_cloud() {
        when(sofarTracker.current()).thenReturn(SofarSamples.state(SofarSamples.reading(), true));
        moveTo(SofarSamples.COLLECTED_AT.plusMinutes(3));

        service.inverter("sofar").as(StepVerifier::create)
            .expectNext(sofarReply(false))
            .verifyComplete();
    }

    //collected longer ago than energy.sofar.cloud.stale-after, or the last read failed: served, marked stale
    @Test
    void should_serve_an_old_or_failed_sofar_reading_as_stale() {
        when(sofarTracker.current()).thenReturn(SofarSamples.state(SofarSamples.reading(), true),
            SofarSamples.state(SofarSamples.reading(), false));
        moveTo(SofarSamples.COLLECTED_AT.plusMinutes(16));

        service.inverter("sofar").as(StepVerifier::create).expectNext(sofarReply(true)).verifyComplete();
        clock.moveBy(Duration.ofMinutes(-14));
        service.inverter("sofar").as(StepVerifier::create).expectNext(sofarReply(true)).verifyComplete();
    }

    //a reading of another registry entry is not this SOFAR's
    @Test
    void should_answer_the_registry_entry_for_a_reading_of_another_sofar() {
        when(sofarTracker.current()).thenReturn(new SofarState(999L, SofarSamples.reading(), SofarSamples.COLLECTED_AT,
            true));

        service.inverter("sofar").as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.stale()).isTrue();
                assertThat(reply.source()).isNull();
                assertThat(reply.pvPower()).isNull();
            })
            .verifyComplete();
    }

    //the clock moved to this house time - through the zone, so that a change of the clocks in between counts
    private void moveTo(final LocalDateTime houseTime) {
        clock.moveBy(Duration.between(clock.instant(), houseTime.atZone(SolaxSamples.HOUSE).toInstant()));
    }

    private static InverterReply sofarReply(final boolean stale) {
        final Map<String, PhaseReply> phases = new LinkedHashMap<>();
        phases.put("a", new PhaseReply(234.4, 0.78, 80.0, null, null, null));
        phases.put("b", new PhaseReply(234.1, 0.80, 90.0, null, null, null));
        phases.put("c", new PhaseReply(235.4, 0.79, 90.0, null, null, null));
        final Map<String, PvReply> pv = new LinkedHashMap<>();
        pv.put("pv1", new PvReply(356.0, 0.77, 270.0));
        pv.put("pv2", new PvReply(0.0, 0.0, 0.0));
        return new InverterReply("sofar", 135L, "SOFAR inverter", "boiler", null, "Grid connected", List.of(), 270.0,
            phases, 200.0, pv, null, 49.99, null, 32.0, 0.26, 5421.5, SofarSamples.COLLECTED_AT, stale,
            "solarman-cloud");
    }

    private static InverterReply solaxReply(final boolean stale) {
        final Map<String, PhaseReply> phases = new LinkedHashMap<>();
        phases.put("a", new PhaseReply(234.0, 2.1, 479.0, null, null, null));
        phases.put("b", new PhaseReply(232.7, 3.6, 817.0, null, null, null));
        phases.put("c", new PhaseReply(234.5, 2.1, 460.0, null, null, null));
        final Map<String, PvReply> pv = new LinkedHashMap<>();
        pv.put("pv1", new PvReply(0.0, 0.0, 0.0));
        pv.put("pv2", new PvReply(0.0, 0.0, 0.0));
        return new InverterReply("solax", ID, "SOLAX inverter", "garage", RunMode.NORMAL, null, null, 1755.0, phases, 0.0, pv,
            0.0, 50.01, 0.95, 48.0, 38.6, 4510.6, READ_AT, stale, "local");
    }

    //HAS-329: the dongle stale, the SolaX cloud of the same inverter fresh - its answer with source cloud, the time of
    //the plant, no run mode and no grid power (neither established in the cloud)
    @Test
    void should_answer_the_cloud_while_the_dongle_is_stale() {
        when(tracker.current()).thenReturn(new SolaxState(ID, reading(EVENING), READ_AT.minusMinutes(5), false));
        when(cloudTracker.current()).thenReturn(new SolaxCloudState(CloudSamples.inverterAt("2026-10-27 20:45:00"),
            null, READ_AT, true));
        final Map<String, PhaseReply> phases = new LinkedHashMap<>();
        phases.put("a", new PhaseReply(235.4, 1.0, 187.0, null, null, null));
        phases.put("b", new PhaseReply(235.7, 1.5, 311.0, null, null, null));
        phases.put("c", new PhaseReply(238.1, 1.5, 328.0, null, null, null));
        final Map<String, PvReply> pv = new LinkedHashMap<>();
        pv.put("pv1", new PvReply(644.1, 1.2, 772.0));
        pv.put("pv2", new PvReply(272.0, 1.1, 301.0));

        service.inverter("solax").as(StepVerifier::create)
            .expectNext(new InverterReply("solax", ID, "SOLAX inverter", "garage", null, null, null, 826.0, phases, 1073.0, pv,
                null, 49.97, 0.86, 48.0, 4.0, 4558.4, LocalDateTime.of(2026, 10, 27, 20, 45), false, "cloud"))
            .verifyComplete();
    }

    //a cloud answer older than its bound, or of another inverter, never stands for the dongle
    @Test
    void should_keep_the_stale_dongle_when_the_cloud_is_old_or_of_another_inverter() {
        when(tracker.current()).thenReturn(new SolaxState(ID, reading(EVENING), READ_AT.minusMinutes(5), false));
        when(cloudTracker.current()).thenReturn(new SolaxCloudState(CloudSamples.inverterAt("2026-10-27 20:33:59"),
            null, READ_AT, true));

        service.inverter("solax").as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.source()).isEqualTo("local"))
            .verifyComplete();

        when(registry.current()).thenReturn(Optional.of(List.of(new Inverter(ID,
            "SOLAX inverter", null, InverterBrand.SOLAX, "H0000000000000", "192.0.2.158",
            "SABCDEFGHI", null, null))));
        when(cloudTracker.current()).thenReturn(new SolaxCloudState(CloudSamples.inverterAt("2026-10-27 20:45:00"),
            null, READ_AT, true));

        service.inverter("solax").as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.source()).isEqualTo("local"))
            .verifyComplete();
    }
}

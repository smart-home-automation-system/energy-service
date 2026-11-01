package cloud.cholewa.energy.inverter;

import cloud.cholewa.energy.api.model.BatteryDirection;
import cloud.cholewa.energy.api.model.BatteryReply;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.solax.SolaxReading;
import cloud.cholewa.energy.solax.SolaxSamples;
import cloud.cholewa.energy.solax.SolaxState;
import cloud.cholewa.energy.registry.InverterRegistry;
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
import java.util.Optional;

import static cloud.cholewa.energy.solax.SolaxSamples.EVENING;
import static cloud.cholewa.energy.solax.SolaxSamples.ID;
import static cloud.cholewa.energy.solax.SolaxSamples.reading;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BatteryServiceTest {

    private static final LocalDateTime READ_AT = LocalDateTime.of(2026, 10, 27, 20, 49);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 20, 49, 10);

    private final SolaxTracker tracker = mock(SolaxTracker.class);
    private final SolaxCloudTracker cloudTracker = mock(SolaxCloudTracker.class);
    private final InverterRegistry registry = mock(InverterRegistry.class);
    private final BatteryService service = new BatteryService(tracker,
        new SolaxProperties(Duration.ofSeconds(15), Duration.ofMinutes(1), 26.5), cloudTracker,
        CloudSamples.PROPERTIES, registry, new SolaxSamples.MovableClock());

    @BeforeEach
    void setUp() {
        when(cloudTracker.current()).thenReturn(new SolaxCloudState(null, null, null, null));
        when(registry.of(InverterBrand.SOLAX)).thenReturn(Optional.of(SolaxSamples.solax("192.0.2.158")));
    }

    //SolaxCloud at the same time: SOC 64 %, 16.9 kWh left of its own estimate
    @Test
    void should_answer_the_battery_with_the_energy_left() {
        when(tracker.current()).thenReturn(new SolaxState(ID, reading(EVENING), READ_AT, true));

        service.battery().as(StepVerifier::create)
            .expectNext(new BatteryReply(64.0, 99.0, -1980.0, BatteryDirection.DISCHARGING, 52.3, -38.6, 20.0, 132,
                16.96, 26.6, 10.2, READ_AT, false, "local"))
            .verifyComplete();
    }

    @Test
    void should_answer_only_stale_before_the_first_reading() {
        assertThat(service.replyOf(new SolaxState(null, null, null, null), NOW)).isEqualTo(
            new BatteryReply(null, null, null, null, null, null, null, null, null, null, null, null, true, null));
    }

    @Test
    void should_serve_an_old_reading_as_stale() {
        assertThat(service.replyOf(new SolaxState(ID, reading(EVENING), READ_AT, true), READ_AT.plusSeconds(61))
            .stale()).isTrue();
    }

    //within 20 W of nothing the battery is idle, above it charging
    @Test
    void should_tell_the_direction_by_the_power() {
        assertThat(direction(15.0)).isEqualTo(BatteryDirection.IDLE);
        assertThat(direction(-20.0)).isEqualTo(BatteryDirection.IDLE);
        assertThat(direction(4800.0)).isEqualTo(BatteryDirection.CHARGING);
        assertThat(direction(-21.0)).isEqualTo(BatteryDirection.DISCHARGING);
    }

    private BatteryDirection direction(final double power) {
        final SolaxReading evening = reading(EVENING);
        final SolaxReading.Battery battery = evening.battery();
        final SolaxReading withPower = new SolaxReading(evening.inverterSerial(), evening.runModeCode(), evening.a(),
            evening.b(), evening.c(), evening.frequency(), evening.powerFactor(), evening.acPower(),
            evening.temperature(), evening.dailyYield(), evening.totalYield(), evening.pv1(), evening.pv2(),
            evening.gridPower(),
            new SolaxReading.Battery(battery.soc(), battery.soh(), power, battery.voltage(), battery.current(),
                battery.temperature(), battery.cycles(), battery.chargedToday(), battery.dischargedToday()),
            evening.gridPoint());
        return service.replyOf(new SolaxState(ID, withPower, READ_AT, true), NOW).direction();
    }

    //HAS-329: the dongle stale, the battery of the SOLAX in the cloud fresh - its answer with source cloud; charged and
    //discharged today are not read from the cloud
    @Test
    void should_answer_the_cloud_while_the_dongle_is_stale() {
        when(cloudTracker.current()).thenReturn(new SolaxCloudState(null,
            CloudSamples.batteryAt("2026-10-27 20:45:00"), NOW, true));

        assertThat(service.replyOf(new SolaxState(ID, reading(EVENING), READ_AT.minusMinutes(10), false), NOW))
            .isEqualTo(new BatteryReply(9.0, 99.0, 146.0, BatteryDirection.CHARGING, 51.8, 3.1, 19.0, 134, 2.39, null,
                null, LocalDateTime.of(2026, 10, 27, 20, 45), false, "cloud"));
        //a cloud answer older than the stale reading of the dongle never replaces it
        assertThat(service.replyOf(new SolaxState(ID, reading(EVENING), READ_AT, false), NOW).source())
            .isEqualTo("local");
    }

    @Test
    void should_never_answer_the_cloud_while_the_dongle_is_fresh_or_the_cloud_old() {
        when(cloudTracker.current()).thenReturn(new SolaxCloudState(null,
            CloudSamples.batteryAt("2026-10-27 20:45:00"), NOW, true));
        assertThat(service.replyOf(new SolaxState(ID, reading(EVENING), READ_AT, true), NOW).source())
            .isEqualTo("local");

        when(cloudTracker.current()).thenReturn(new SolaxCloudState(null,
            CloudSamples.batteryAt("2026-10-27 20:30:00"), NOW, true));
        assertThat(service.replyOf(new SolaxState(ID, reading(EVENING), READ_AT, false), NOW).source())
            .isEqualTo("local");
    }
}

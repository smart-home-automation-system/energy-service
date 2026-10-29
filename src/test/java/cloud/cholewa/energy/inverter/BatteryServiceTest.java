package cloud.cholewa.energy.inverter;

import cloud.cholewa.energy.api.model.BatteryDirection;
import cloud.cholewa.energy.api.model.BatteryReply;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.solax.SolaxReading;
import cloud.cholewa.energy.solax.SolaxSamples;
import cloud.cholewa.energy.solax.SolaxState;
import cloud.cholewa.energy.solax.SolaxTracker;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;

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
    private final BatteryService service = new BatteryService(tracker,
        new SolaxProperties(Duration.ofSeconds(15), Duration.ofMinutes(1), 26.5), new SolaxSamples.MovableClock());

    //SolaxCloud at the same time: SOC 64 %, 16.9 kWh left of its own estimate
    @Test
    void should_answer_the_battery_with_the_energy_left() {
        when(tracker.current()).thenReturn(new SolaxState(ID, reading(EVENING), READ_AT, true));

        service.battery().as(StepVerifier::create)
            .expectNext(new BatteryReply(64.0, 99.0, -1980.0, BatteryDirection.DISCHARGING, 52.3, -38.6, 20.0, 132,
                16.96, 26.6, 10.2, READ_AT, false))
            .verifyComplete();
    }

    @Test
    void should_answer_only_stale_before_the_first_reading() {
        assertThat(service.replyOf(new SolaxState(null, null, null, null), NOW)).isEqualTo(
            new BatteryReply(null, null, null, null, null, null, null, null, null, null, null, null, true));
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
                battery.temperature(), battery.cycles(), battery.chargedToday(), battery.dischargedToday()));
        return service.replyOf(new SolaxState(ID, withPower, READ_AT, true), NOW).direction();
    }
}

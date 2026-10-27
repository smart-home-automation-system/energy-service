package cloud.cholewa.energy.meter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;

import static cloud.cholewa.energy.meter.MeterSamples.COUNTERS;
import static cloud.cholewa.energy.meter.MeterSamples.METER;
import static cloud.cholewa.energy.meter.MeterSamples.READINGS;
import static cloud.cholewa.energy.meter.MeterSamples.bytes;
import static org.assertj.core.api.Assertions.assertThat;

//The router on the routing keys the broker of the house carries (HAS-327), with the real decoder of shelly-client
class MeterEventRouterTest {

    private final MeterSamples.MovableClock clock = new MeterSamples.MovableClock();
    private final GridMeterTracker tracker = new GridMeterTracker(clock);
    private final MeterEventRouter router = new MeterEventRouter(
        new MeterProperties(METER, "localhost:1", Duration.ofMinutes(2)),
        new ShellyMqttDecoder(JsonMapper.builder().build()), tracker);
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();

    @BeforeEach
    void setUp() {
        log.start();
        ((Logger) LoggerFactory.getLogger(MeterEventRouter.class)).addAppender(log);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(MeterEventRouter.class)).detachAppender(log);
    }

    @Test
    void should_take_the_readings_of_the_meter() {
        router.route(METER + ".status.em:0", bytes(READINGS)).as(StepVerifier::create).verifyComplete();

        final GridMeterState state = tracker.current();
        assertThat(state.readings().getTotalActPower()).isEqualTo(-113.5);
        assertThat(state.readings().getaVoltage()).isEqualTo(236.8);
        assertThat(state.readingsAt()).isEqualTo(LocalDateTime.of(2026, 10, 27, 11, 0));
        assertThat(state.online()).isTrue();
        assertThat(state.counters()).isNull();
    }

    @Test
    void should_take_the_counters_of_the_meter() {
        router.route(METER + ".status.emdata:0", bytes(COUNTERS)).as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().counters().getTotalActRet()).isEqualTo(2158851.5);
        assertThat(tracker.current().readings()).isNull();
    }

    //the id of the topic is the id of the device in its own letters; the manifest may write it otherwise
    @Test
    void should_match_the_meter_whatever_the_case_of_its_id() {
        router.route(METER.toUpperCase() + ".status.em:0", bytes(READINGS)).as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().readings()).isNotNull();
    }

    @Test
    void should_take_the_last_will_of_the_meter() {
        router.route(METER + ".status.em:0", bytes(READINGS)).as(StepVerifier::create).verifyComplete();
        router.route(METER + ".online", bytes("false")).as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().online()).isFalse();
        assertThat(tracker.current().readings()).isNotNull();
    }

    //DoD: a message of another device is ignored without a WARN - about 130 a minute arrive, not one of them is
    //the meter's but some eleven; their bodies are not even read, so JSON of another shape changes nothing
    @ParameterizedTest
    @ValueSource(strings = { "shellyplus2pm-a1b2c3d4e5f6.status.em:0", "shellyplus2pm-a1b2c3d4e5f6.status.cover:0",
        "shellyplus2pm-a1b2c3d4e5f6.online", "eaton.blinds.45", "x/events/rpc", "",
        //a component of the meter that is not its readings: the gate sensors of the garage, its temperature
        METER + ".status.bthomesensor:202", METER + ".status.temperature:0", METER + ".status.modbus",
        METER + ".status.em:1", METER + ".status.sys" })
    void should_leave_what_is_not_the_readings_of_the_meter_without_a_word(final String key) {
        router.route(key, bytes("not json at all")).as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current()).isEqualTo(GridMeterState.UNKNOWN);
        assertThat(log.list).isEmpty();
    }

    @Test
    void should_leave_a_message_without_a_routing_key() {
        router.route(null, bytes(READINGS)).as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current()).isEqualTo(GridMeterState.UNKNOWN);
    }

    //a body that is not what its topic carries is a WARN naming the topic, never an error out of the listener
    @ParameterizedTest
    @ValueSource(strings = { "not json", "{}", "{\"id\":1,\"total_act_power\":5.0}" })
    void should_warn_of_a_body_of_the_meter_that_is_not_its_readings(final String body) {
        router.route(METER + ".status.em:0", bytes(body)).as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().readings()).isNull();
        assertThat(log.list).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage()).contains("em:0").doesNotContain(body);
        });
    }

    //the later arrival wins, whichever channel it came by
    @Test
    void should_replace_the_readings_with_the_newer_ones() {
        router.route(METER + ".status.em:0", bytes(READINGS)).as(StepVerifier::create).verifyComplete();
        clock.moveBy(Duration.ofSeconds(5));
        router.route(METER + ".status.em:0", bytes(READINGS.replace("-113.5", "250.0")))
            .as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().readings().getTotalActPower()).isEqualTo(250.0);
        assertThat(tracker.current().readingsAt()).isEqualTo(LocalDateTime.of(2026, 10, 27, 11, 0, 5));
    }
}

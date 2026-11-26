package cloud.cholewa.energy.solax;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.energy.registry.Inverter;
import cloud.cholewa.energy.registry.InverterRegistry;
import cloud.cholewa.home.model.InverterBrand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static cloud.cholewa.energy.solax.SolaxSamples.solax;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the reader of the settings of the SOLAX (HAS-518): the dongle asked only when a read is due - at first, every interval
//and once after a write of the service -, a failed read keeping the last settings until they go stale
class SolaxSettingsReaderTest {

    private static final Inverter SOLAX = solax("192.0.2.158");
    private static final SolaxSettings BACK_UP = new SolaxSettings(161, WorkMode.BACK_UP, 75, 80);
    private static final SolaxSettings RAISED = new SolaxSettings(161, WorkMode.BACK_UP, 80, 80);

    private final SolaxSamples.MovableClock clock = new SolaxSamples.MovableClock();
    private final InverterRegistry registry = mock(InverterRegistry.class);
    private final SolaxClient client = mock(SolaxClient.class);
    private final SolaxSettingsProperties properties = new SolaxSettingsProperties(Duration.ofMinutes(5),
        Duration.ofSeconds(20), Duration.ofMinutes(15));
    private final SolaxSettingsTracker tracker = new SolaxSettingsTracker(properties, clock);
    private final SolaxSettingsReader reader = new SolaxSettingsReader(registry, client, tracker, properties, clock);
    private final ListAppender<ILoggingEvent> readerLog = new ListAppender<>();

    @BeforeEach
    void setUp() {
        readerLog.start();
        ((Logger) LoggerFactory.getLogger(SolaxSettingsReader.class)).addAppender(readerLog);
        when(registry.of(InverterBrand.SOLAX)).thenReturn(Optional.of(SOLAX));
        when(client.readSetData(SOLAX)).thenReturn(Mono.just(BACK_UP));
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(SolaxSettingsReader.class)).detachAppender(readerLog);
    }

    private void pass() {
        reader.readIfDue().as(StepVerifier::create).verifyComplete();
    }

    private List<String> warnings() {
        return readerLog.list.stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    @Test
    void should_hand_the_settings_to_the_tracker_with_the_time_of_the_house() {
        pass();

        assertThat(tracker.current()).contains(new SolaxSettingsTracker.Read(BACK_UP,
            LocalDateTime.of(2026, 10, 27, 20, 49)));
    }

    @Test
    void should_ask_the_dongle_once_an_interval() {
        pass();
        clock.moveBy(Duration.ofMinutes(4).plusSeconds(50));
        pass();
        verify(client, times(1)).readSetData(SOLAX);

        clock.moveBy(Duration.ofSeconds(10));
        pass();

        verify(client, times(2)).readSetData(SOLAX);
    }

    //the command needs its seconds to reach the inverter: read after-write later, once, then every interval again
    @Test
    void should_read_again_once_twenty_seconds_after_a_write_of_the_service() {
        pass();
        clock.moveBy(Duration.ofMinutes(1));
        tracker.wrote();
        when(client.readSetData(SOLAX)).thenReturn(Mono.just(RAISED));

        clock.moveBy(Duration.ofSeconds(10));
        pass();
        verify(client, times(1)).readSetData(SOLAX);

        clock.moveBy(Duration.ofSeconds(10));
        pass();
        clock.moveBy(Duration.ofSeconds(10));
        pass();

        verify(client, times(2)).readSetData(SOLAX);
        assertThat(tracker.current().map(SolaxSettingsTracker.Read::settings)).contains(RAISED);
    }

    //the same Mono of the scheduler subscribed again sees the time of each run
    @Test
    void should_see_the_time_of_every_run_of_the_same_pass() {
        final Mono<Void> scheduled = reader.read();

        scheduled.as(StepVerifier::create).verifyComplete();
        clock.moveBy(Duration.ofMinutes(5));
        scheduled.as(StepVerifier::create).verifyComplete();

        verify(client, times(2)).readSetData(SOLAX);
    }

    @Test
    void should_ask_nothing_without_a_solax_with_an_address_and_a_registration_number() {
        when(registry.of(InverterBrand.SOLAX)).thenReturn(Optional.empty());

        pass();

        verify(client, never()).readSetData(any());
        assertThat(tracker.current()).isEmpty();

        when(registry.of(InverterBrand.SOLAX)).thenReturn(Optional.of(SOLAX));
        pass();

        assertThat(tracker.current()).isPresent();
    }

    //a failed read is an attempt: the dongle is asked again an interval later, the settings before it kept until they
    //are stale, and the failure is one WARN
    @Test
    void should_keep_the_last_settings_through_a_failed_read_until_they_are_stale() {
        pass();
        when(client.readSetData(SOLAX))
            .thenReturn(Mono.error(new SolaxCallException("The dongle did not answer: timeout")));

        clock.moveBy(Duration.ofMinutes(5));
        pass();
        clock.moveBy(Duration.ofSeconds(10));
        pass();
        clock.moveBy(Duration.ofMinutes(5));
        pass();

        verify(client, times(3)).readSetData(SOLAX);
        assertThat(tracker.current().map(SolaxSettingsTracker.Read::settings)).contains(BACK_UP);
        assertThat(warnings()).containsExactly(
            "The settings of the SOLAX could not be read: The dongle did not answer: timeout");

        clock.moveBy(Duration.ofMinutes(5));

        assertThat(tracker.current()).isEmpty();
    }

    @Test
    void should_know_no_settings_before_the_first_read() {
        assertThat(tracker.current()).isEmpty();
        assertThat(tracker.wroteAt()).isEmpty();
    }

    @Test
    void should_be_bounded_below_its_delay() {
        assertThat(SolaxSettingsReader.LIMIT).isLessThan(Duration.ofSeconds(10));
    }
}

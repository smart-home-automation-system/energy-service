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
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static cloud.cholewa.energy.solax.SolaxSamples.EVENING;
import static cloud.cholewa.energy.solax.SolaxSamples.reading;
import static cloud.cholewa.energy.solax.SolaxSamples.solax;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SolaxReaderTest {

    private static final Inverter SOLAX = solax("192.0.2.158");

    private final SolaxSamples.MovableClock clock = new SolaxSamples.MovableClock();
    private final InverterRegistry registry = mock(InverterRegistry.class);
    private final SolaxClient client = mock(SolaxClient.class);
    private final SolaxTracker tracker = new SolaxTracker(clock);
    private final SolaxReader reader = new SolaxReader(registry, client, tracker, clock);
    private final ListAppender<ILoggingEvent> readerLog = new ListAppender<>();

    @BeforeEach
    void setUp() {
        readerLog.start();
        ((Logger) LoggerFactory.getLogger(SolaxReader.class)).addAppender(readerLog);
        when(registry.current()).thenReturn(Optional.of(List.of(SOLAX)));
        when(registry.of(InverterBrand.SOLAX)).thenReturn(Optional.of(SOLAX));
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(SolaxReader.class)).detachAppender(readerLog);
    }

    @Test
    void should_hand_the_reading_to_the_tracker() {
        when(client.read(SOLAX)).thenReturn(Mono.just(reading(EVENING)));

        reader.read().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().reachable()).isTrue();
        assertThat(tracker.current().inverterId()).isEqualTo(SolaxSamples.ID);
        assertThat(tracker.current().readingAt()).isEqualTo(LocalDateTime.of(2026, 10, 27, 20, 49));
        assertThat(tracker.current().reading().battery().soc()).isEqualTo(64.0);
    }

    //nothing is asked before the registry was read once: the SOLAX is not known, neither reachable nor not
    @Test
    void should_read_nothing_before_the_registry_is_known() {
        when(registry.current()).thenReturn(Optional.empty());
        when(registry.of(InverterBrand.SOLAX)).thenReturn(Optional.empty());

        reader.read().as(StepVerifier::create).verifyComplete();

        verify(client, never()).read(any());
        assertThat(tracker.current().reachable()).isNull();
        assertThat(warnings()).isEmpty();
    }

    //a SOLAX gone from the registry (removed, switched off) is no longer reachable; its last reading is kept, stale
    @Test
    void should_mark_a_solax_gone_from_the_registry_unreachable() {
        when(client.read(SOLAX)).thenReturn(Mono.just(reading(EVENING)));
        reader.read().as(StepVerifier::create).verifyComplete();
        when(registry.of(InverterBrand.SOLAX)).thenReturn(Optional.empty());

        reader.read().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().reachable()).isFalse();
        assertThat(tracker.current().reading()).isNotNull();
    }

    //two causes of the same exception class are two kinds: the second is said at once
    @Test
    void should_warn_at_once_of_another_cause_of_the_same_class() {
        when(client.read(SOLAX)).thenReturn(
            Mono.error(new SolaxCallException("The dongle did not answer: connection timed out after 3000 ms")),
            Mono.error(new SolaxCallException("The dongle did not answer: connection refused")),
            Mono.error(new SolaxCallException("The dongle answered 500")));

        reader.read().as(StepVerifier::create).verifyComplete();
        reader.read().as(StepVerifier::create).verifyComplete();
        reader.read().as(StepVerifier::create).verifyComplete();

        assertThat(warnings()).containsExactly(
            "The SOLAX could not be read: The dongle did not answer: connection timed out after 3000 ms",
            "The SOLAX could not be read: The dongle answered 500");
    }

    //a bare number of energy.solax.interval is seconds, as SolaxProperties binds it - not the milliseconds of @Scheduled
    @Test
    void should_schedule_the_interval_in_seconds() throws NoSuchMethodException {
        final Scheduled scheduled = SolaxReader.class.getDeclaredMethod("read").getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString()).isEqualTo("${energy.solax.interval}");
        assertThat(scheduled.timeUnit()).isEqualTo(TimeUnit.SECONDS);
    }

    //a SOLAX without its address or its registration number cannot be asked: one WARN, not one every read
    @Test
    void should_read_nothing_from_a_solax_without_what_its_dongle_needs() {
        final Inverter withoutNumber = new Inverter(SolaxSamples.ID, "SOLAX inverter", null, InverterBrand.SOLAX,
            SolaxSamples.SERIAL, "192.0.2.158", null, null);
        when(registry.of(InverterBrand.SOLAX)).thenReturn(Optional.of(withoutNumber));

        reader.read().as(StepVerifier::create).verifyComplete();
        reader.read().as(StepVerifier::create).verifyComplete();

        verify(client, never()).read(any());
        assertThat(warnings()).containsExactly("The SOLAX of the registry could not be read: "
            + "No switched-on SOLAX with an address and a registration number is registered");
    }

    //a failed read keeps the last reading, served as stale by the API
    @Test
    void should_mark_the_solax_unreachable_and_keep_its_last_reading_when_a_read_fails() {
        when(client.read(SOLAX)).thenReturn(Mono.just(reading(EVENING)),
            Mono.error(new SolaxCallException("The dongle did not answer")));

        reader.read().as(StepVerifier::create).verifyComplete();
        clock.moveBy(Duration.ofSeconds(15));
        reader.read().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().reachable()).isFalse();
        assertThat(tracker.current().readingAt()).isEqualTo(LocalDateTime.of(2026, 10, 27, 20, 49));
        assertThat(tracker.current().reading()).isNotNull();
    }

    //DoD: a wrong registration number is a failed call with one WARN an hour, not a crash
    @Test
    void should_warn_of_a_refused_registration_number_once_an_hour() {
        when(client.read(SOLAX)).thenReturn(Mono.error(new RegistrationRefusedException()));

        reader.read().as(StepVerifier::create).verifyComplete();
        clock.moveBy(Duration.ofMinutes(59));
        reader.read().as(StepVerifier::create).verifyComplete();
        clock.moveBy(Duration.ofMinutes(1));
        reader.read().as(StepVerifier::create).verifyComplete();

        assertThat(warnings()).containsExactly(
            "The SOLAX could not be read: The dongle refused the registration number of the registry",
            "The SOLAX could not be read: The dongle refused the registration number of the registry");
        assertThat(tracker.current().reachable()).isFalse();
    }

    //another kind of failure is said at once; the return is an INFO
    @Test
    void should_warn_of_another_failure_at_once_and_tell_the_return() {
        when(client.read(SOLAX)).thenReturn(Mono.error(new RegistrationRefusedException()),
            Mono.error(new SolaxCallException("The dongle answered 500")), Mono.just(reading(EVENING)));

        reader.read().as(StepVerifier::create).verifyComplete();
        reader.read().as(StepVerifier::create).verifyComplete();
        reader.read().as(StepVerifier::create).verifyComplete();

        assertThat(warnings()).hasSize(2);
        assertThat(readerLog.list).filteredOn(event -> event.getLevel() == Level.INFO)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("The SOLAX is read again");
    }

    //a dongle that answers for another inverter is a misplaced registry entry: nothing of it is taken
    @Test
    void should_refuse_an_answer_for_another_inverter() {
        final Inverter other = new Inverter(SolaxSamples.ID, "SOLAX inverter", null, InverterBrand.SOLAX,
            "H0000000000000", "192.0.2.158", SolaxSamples.REGISTRATION, null);
        when(registry.of(InverterBrand.SOLAX)).thenReturn(Optional.of(other));
        when(client.read(other)).thenReturn(Mono.just(reading(EVENING)));

        reader.read().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().reading()).isNull();
        assertThat(warnings()).containsExactly("The SOLAX could not be read: "
            + "The dongle answers for another inverter than H0000000000000 of the registry");
    }

    //a dongle that accepts the call and never answers ends the pass at its bound, below the shortest interval
    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        when(client.read(SOLAX)).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(reader::read)
            .expectSubscription()
            .expectNoEvent(SolaxReader.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
        assertThat(SolaxReader.LIMIT.compareTo(Duration.ofSeconds(10))).isNegative();
    }

    //a reactive @Scheduled method is called once and subscribed to at every run: the registry is asked at every run
    @Test
    void should_read_the_registry_at_every_run() {
        when(client.read(SOLAX)).thenReturn(Mono.just(reading(EVENING)));
        final Mono<Void> pass = reader.read();

        pass.as(StepVerifier::create).verifyComplete();
        when(registry.of(InverterBrand.SOLAX)).thenReturn(Optional.empty());
        clock.moveBy(Duration.ofSeconds(15));
        pass.as(StepVerifier::create).verifyComplete();

        verify(client).read(SOLAX);
    }

    private List<String> warnings() {
        return readerLog.list.stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }
}

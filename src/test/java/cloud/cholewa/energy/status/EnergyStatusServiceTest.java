package cloud.cholewa.energy.status;

import cloud.cholewa.energy.api.model.GridStateReply;
import cloud.cholewa.energy.api.model.InverterSettingsReply;
import cloud.cholewa.energy.api.model.SheddingReply;
import cloud.cholewa.energy.api.model.SourceStatusReply;
import cloud.cholewa.energy.meter.GridMeterTracker;
import cloud.cholewa.energy.frequency.FrequencyState;
import cloud.cholewa.energy.meter.GridMeterState;
import cloud.cholewa.energy.outage.GridOutageService;
import cloud.cholewa.energy.shedding.LoadSheddingWatch;
import cloud.cholewa.energy.shedding.Shedding;
import cloud.cholewa.energy.solax.SolaxSamples;
import cloud.cholewa.energy.sofar.SofarSamples;
import cloud.cholewa.energy.sofar.SofarState;
import cloud.cholewa.energy.sofar.SofarTracker;
import cloud.cholewa.energy.solax.SolaxSettings;
import cloud.cholewa.energy.solax.SolaxSettingsTracker;
import cloud.cholewa.energy.solax.SolaxState;
import cloud.cholewa.energy.solax.SolaxTracker;
import cloud.cholewa.energy.solax.WorkMode;
import cloud.cholewa.energy.solaxcloud.SolaxCloudState;
import cloud.cholewa.energy.solaxcloud.SolaxCloudTracker;
import cloud.cholewa.shelly.model.EmDataStatus;
import cloud.cholewa.shelly.model.EmStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnergyStatusServiceTest {

    private final GridMeterTracker tracker = mock(GridMeterTracker.class);
    private final SolaxTracker solaxTracker = mock(SolaxTracker.class);
    private final SolaxCloudTracker cloudTracker = mock(SolaxCloudTracker.class);
    private final SofarTracker sofarTracker = mock(SofarTracker.class);
    private final GridOutageService gridOutageService = mock(GridOutageService.class);
    private final FrequencyState frequencyState = mock(FrequencyState.class);
    private final SolaxSettingsTracker settingsTracker = mock(SolaxSettingsTracker.class);
    private final LoadSheddingWatch loadSheddingWatch = mock(LoadSheddingWatch.class);
    private final EnergyStatusService energyStatusService = new EnergyStatusService(tracker, solaxTracker,
        cloudTracker, sofarTracker, gridOutageService, frequencyState, settingsTracker, loadSheddingWatch);

    @BeforeEach
    void setUp() {
        when(solaxTracker.current()).thenReturn(new SolaxState(null, null, null, null));
        when(cloudTracker.current()).thenReturn(new SolaxCloudState(null, null, null, null));
        when(sofarTracker.current()).thenReturn(new SofarState(null, null, null, null));
        when(settingsTracker.current()).thenReturn(Optional.empty());
    }

    //the settings of the SOLAX as its dongle last answered them, left out while not known (HAS-518)
    @Test
    void should_answer_the_settings_of_the_inverter_while_they_are_known() {
        when(tracker.current()).thenReturn(new GridMeterState(null, null, null, null, null, null));
        when(gridOutageService.grid()).thenReturn(GridStateReply.notKnown());
        when(frequencyState.current()).thenReturn(new FrequencyState.Snapshot(null, null));
        when(settingsTracker.current()).thenReturn(Optional.empty());

        energyStatusService.reply()
            .as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.inverter()).isNull())
            .verifyComplete();

        when(settingsTracker.current()).thenReturn(Optional.of(new SolaxSettingsTracker.Read(
            new SolaxSettings(164, WorkMode.FEED_IN_PRIORITY, 75, 80), LocalDateTime.of(2026, 11, 26, 9, 6))));

        energyStatusService.reply()
            .as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.inverter()).isEqualTo(new InverterSettingsReply(
                "feed-in priority", 75, 80, LocalDateTime.of(2026, 11, 26, 9, 6))))
            .verifyComplete();
    }

    //the load shedding of its watch, left out until it was read after a start (HAS-523)
    @Test
    void should_answer_the_load_shedding_once_it_is_known() {
        when(tracker.current()).thenReturn(new GridMeterState(null, null, null, null, null, null));
        when(gridOutageService.grid()).thenReturn(GridStateReply.notKnown());
        when(frequencyState.current()).thenReturn(new FrequencyState.Snapshot(null, null));
        when(loadSheddingWatch.current()).thenReturn(Optional.empty());

        energyStatusService.reply()
            .as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.shedding()).isNull())
            .verifyComplete();

        when(loadSheddingWatch.current()).thenReturn(Optional.of(new Shedding(true,
            LocalDateTime.of(2026, 11, 28, 12, 0), LocalDateTime.of(2026, 11, 28, 13, 10))));

        energyStatusService.reply()
            .as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.shedding()).isEqualTo(new SheddingReply(true,
                LocalDateTime.of(2026, 11, 28, 12, 0), LocalDateTime.of(2026, 11, 28, 13, 10))))
            .verifyComplete();
    }

    //the grid of the outage watch with the frequency of the last pass of the frequency watch (HAS-503)
    @Test
    void should_answer_the_grid_with_its_frequency() {
        when(tracker.current()).thenReturn(new GridMeterState(null, null, null, null, null, null));
        when(gridOutageService.grid()).thenReturn(new GridStateReply("present", null));
        when(frequencyState.current()).thenReturn(new FrequencyState.Snapshot(51.0, true));

        energyStatusService.reply()
            .as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.grid()).isEqualTo(new GridStateReply("present", null, 51.0, true)))
            .verifyComplete();
    }

    //after a start nothing is known: every source is there, in the order of the dashboard, and none is known
    @Test
    void should_answer_every_source_as_not_known_before_anything_was_heard() {
        when(tracker.current()).thenReturn(new GridMeterState(null, null, null, null, null, null));

        energyStatusService.status()
            .as(StepVerifier::create)
            .expectNext(List.of(
                new SourceStatusReply("meter", null, null),
                new SourceStatusReply("solax", null, null),
                new SourceStatusReply("sofar", null, null),
                new SourceStatusReply("solaxCloud", null, null)
            ))
            .verifyComplete();
    }

    //the meter with its newest reading - the counters came after the readings here
    @Test
    void should_answer_the_meter_with_its_newest_reading() {
        when(tracker.current()).thenReturn(new GridMeterState(new EmStatus(), LocalDateTime.of(2026, 10, 27, 11, 0),
            new EmDataStatus(), LocalDateTime.of(2026, 10, 27, 11, 0, 30), true, true));

        energyStatusService.status()
            .as(StepVerifier::create)
            .expectNextMatches(sources -> sources.getFirst()
                .equals(new SourceStatusReply("meter", true, LocalDateTime.of(2026, 10, 27, 11, 0, 30))))
            .verifyComplete();
    }

    //its last will: not reachable, with the time of what it said last
    @Test
    void should_answer_a_meter_that_left_the_broker_as_not_reachable() {
        when(tracker.current()).thenReturn(new GridMeterState(new EmStatus(), LocalDateTime.of(2026, 10, 27, 11, 0),
            null, null, false, null));

        energyStatusService.status()
            .as(StepVerifier::create)
            .expectNextMatches(sources -> sources.getFirst()
                .equals(new SourceStatusReply("meter", false, LocalDateTime.of(2026, 10, 27, 11, 0))))
            .verifyComplete();
    }

    //the SOLAX (HAS-328): reachable as its last read went, with the time of its last reading
    @Test
    void should_answer_the_solax_with_its_last_reading() {
        when(tracker.current()).thenReturn(new GridMeterState(null, null, null, null, null, null));
        when(solaxTracker.current()).thenReturn(new SolaxState(SolaxSamples.ID,
            SolaxSamples.reading(SolaxSamples.EVENING), LocalDateTime.of(2026, 10, 27, 20, 49), true));

        energyStatusService.status()
            .as(StepVerifier::create)
            .expectNextMatches(sources -> sources.get(1)
                .equals(new SourceStatusReply("solax", true, LocalDateTime.of(2026, 10, 27, 20, 49))))
            .verifyComplete();
    }

    //a failed read: not reachable, with the time of the reading it still holds
    @Test
    void should_answer_a_solax_whose_last_read_failed_as_not_reachable() {
        when(tracker.current()).thenReturn(new GridMeterState(null, null, null, null, null, null));
        when(solaxTracker.current()).thenReturn(new SolaxState(SolaxSamples.ID,
            SolaxSamples.reading(SolaxSamples.EVENING), LocalDateTime.of(2026, 10, 27, 20, 49), false));

        energyStatusService.status()
            .as(StepVerifier::create)
            .expectNextMatches(sources -> sources.get(1)
                .equals(new SourceStatusReply("solax", false, LocalDateTime.of(2026, 10, 27, 20, 49))))
            .verifyComplete();
    }

    //HAS-330: the SOFAR - whether the last read brought a fresh reading, with the time its logger collected it
    @Test
    void should_answer_the_sofar_with_the_time_its_reading_was_collected() {
        when(tracker.current()).thenReturn(new GridMeterState(null, null, null, null, null, null));
        when(sofarTracker.current()).thenReturn(SofarSamples.state(SofarSamples.reading(), false));

        energyStatusService.status()
            .as(StepVerifier::create)
            .expectNextMatches(sources -> sources.get(2)
                .equals(new SourceStatusReply("sofar", false, SofarSamples.COLLECTED_AT)))
            .verifyComplete();
    }

    //HAS-329: the SolaX cloud is its last read
    @Test
    void should_answer_the_cloud_with_its_last_read() {
        final LocalDateTime readAt = LocalDateTime.of(2026, 11, 1, 12, 53);
        when(tracker.current()).thenReturn(new GridMeterState(null, null, null, null, null, null));
        when(cloudTracker.current()).thenReturn(new SolaxCloudState(null, null, readAt, false));

        energyStatusService.status().as(StepVerifier::create)
            .assertNext(sources -> assertThat(sources.get(3))
                .isEqualTo(new SourceStatusReply("solaxCloud", false, readAt)))
            .verifyComplete();
    }
}

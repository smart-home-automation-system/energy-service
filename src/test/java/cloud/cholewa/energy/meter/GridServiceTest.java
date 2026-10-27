package cloud.cholewa.energy.meter;

import cloud.cholewa.energy.api.model.GridDirection;
import cloud.cholewa.energy.api.model.GridReply;
import cloud.cholewa.energy.api.model.PhaseReply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;

import static cloud.cholewa.energy.meter.MeterSamples.METER;
import static cloud.cholewa.energy.meter.MeterSamples.counters;
import static cloud.cholewa.energy.meter.MeterSamples.readings;
import static org.assertj.core.api.Assertions.assertThat;

class GridServiceTest {

    private final MeterSamples.MovableClock clock = new MeterSamples.MovableClock();
    private final GridMeterTracker tracker = new GridMeterTracker(clock);
    private final GridService service = new GridService(tracker,
        new MeterProperties(METER, "localhost:1", Duration.ofMinutes(2)), clock);

    //nothing heard since the start: nothing is answered but that it is not fresh - never zeros
    @Test
    void should_answer_nothing_but_stale_before_the_first_readings() {
        service.grid().as(StepVerifier::create)
            .expectNext(new GridReply(null, null, null, null, null, null, true))
            .verifyComplete();
    }

    @Test
    void should_answer_the_readings_and_the_counters() {
        tracker.readings(readings(-113.5));
        tracker.counters(counters(891011.75, 2158851.5));
        clock.moveBy(Duration.ofSeconds(10));

        service.grid().as(StepVerifier::create).assertNext(grid -> {
            assertThat(grid.phases()).containsOnlyKeys("a", "b", "c");
            assertThat(grid.phases().get("a")).isEqualTo(new PhaseReply(236.8, 1.652, -171.4, 391.2, 0.44, 50.0));
            //what the meter did not send of a phase is left out
            assertThat(grid.phases().get("b")).isEqualTo(new PhaseReply(235.4, null, 31.2, null, null, null));
            assertThat(grid.totalActivePower()).isEqualTo(-113.5);
            assertThat(grid.importEnergy()).isEqualTo(891.01175);
            assertThat(grid.exportEnergy()).isEqualTo(2158.8515);
            assertThat(grid.direction()).isEqualTo(GridDirection.EXPORT);
            assertThat(grid.measuredAt()).isEqualTo(LocalDateTime.of(2026, 10, 27, 11, 0));
            assertThat(grid.stale()).isFalse();
        }).verifyComplete();
    }

    //energy.meter.stale-after (PT2M): at two minutes still fresh, a second later stale
    @Test
    void should_answer_readings_older_than_stale_after_as_stale() {
        tracker.readings(readings(-113.5));

        clock.moveBy(Duration.ofMinutes(2));
        service.grid().as(StepVerifier::create).assertNext(grid -> assertThat(grid.stale()).isFalse())
            .verifyComplete();

        clock.moveBy(Duration.ofSeconds(1));
        service.grid().as(StepVerifier::create).assertNext(grid -> {
            assertThat(grid.stale()).isTrue();
            assertThat(grid.totalActivePower()).isEqualTo(-113.5);
        }).verifyComplete();
    }

    //the last will of the meter: what it said last is answered, as stale
    @Test
    void should_answer_the_readings_of_a_meter_that_left_the_broker_as_stale() {
        tracker.readings(readings(-113.5));
        tracker.online(false);

        service.grid().as(StepVerifier::create).assertNext(grid -> assertThat(grid.stale()).isTrue())
            .verifyComplete();
    }

    //the dead band of 20 W around nothing
    @ParameterizedTest
    @CsvSource({ "250.0, IMPORT", "20.1, IMPORT", "20.0, IDLE", "0.0, IDLE", "-20.0, IDLE", "-20.1, EXPORT",
        "-1500.0, EXPORT" })
    void should_tell_the_direction_by_the_total_active_power(final double total, final GridDirection direction) {
        tracker.readings(readings(total));

        service.grid().as(StepVerifier::create).assertNext(grid -> assertThat(grid.direction()).isEqualTo(direction))
            .verifyComplete();
    }

    @Test
    void should_leave_the_direction_out_without_the_total_active_power() {
        tracker.readings(readings(0.0).totalActPower(null));

        service.grid().as(StepVerifier::create).assertNext(grid -> {
            assertThat(grid.totalActivePower()).isNull();
            assertThat(grid.direction()).isNull();
        }).verifyComplete();
    }
}

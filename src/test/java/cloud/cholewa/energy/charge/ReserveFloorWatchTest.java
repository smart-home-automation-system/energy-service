package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.api.model.InverterSettingsReply;
import cloud.cholewa.energy.api.model.ReserveReply;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
import cloud.cholewa.energy.solax.SolaxSamples;
import cloud.cholewa.energy.solax.SolaxSettings;
import cloud.cholewa.energy.solax.WorkMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static cloud.cholewa.energy.charge.ChargeSamples.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the watch of a battery reserve the inverter does not hold (HAS-518) on its collaborators - the reserve of the
//service, the settings read from the dongle, the charges, the table and the broker - and what GET /battery/reserve
//answers of them
class ReserveFloorWatchTest {

    private static final SolaxSettings LOW_FLOOR = new SolaxSettings(161, WorkMode.BACK_UP, 75, 80);
    private static final SolaxSettings HELD = new SolaxSettings(161, WorkMode.BACK_UP, 80, 80);
    private static final SolaxSettings SELF_USE = new SolaxSettings(160, WorkMode.SELF_USE, 75, 80);

    private final ChargeFixture fixture = new ChargeFixture();
    private final SourceAlertRepository alerts = mock(SourceAlertRepository.class);
    private final ReserveFloorWatch watch = new ReserveFloorWatch(fixture.reserve, fixture.repository,
        fixture.settings, alerts, fixture.publisher,
        new ReserveFloorProperties(Duration.ofMinutes(10), Duration.ofHours(1)), fixture.clock);
    private final List<SourceAlertEntity> rows = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(8);
    private final AtomicBoolean databaseDown = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        fixture.reserveRow.set(ChargeSamples.reserveHeld());
        when(fixture.publisher.publishReminder(anyString())).thenReturn(Mono.empty());
        when(alerts.findByClosedAtIsNullAndSourceAndKind("solax", "reserve")).thenAnswer(call -> Flux.fromIterable(
            rows.stream().filter(row -> row.closedAt() == null).toList()));
        when(alerts.save(any())).thenAnswer(call -> {
            final SourceAlertEntity row = call.getArgument(0);
            if (databaseDown.get()) {
                return Mono.error(new IllegalStateException("database"));
            }
            final SourceAlertEntity saved = row.id() != null ? row : new SourceAlertEntity(ids.incrementAndGet(),
                row.source(), row.kind(), row.openedAt(), row.lastToldAt(), row.closedAt(), row.detail());
            rows.removeIf(held -> held.id().equals(saved.id()));
            rows.add(saved);
            return Mono.just(saved);
        });
    }

    private void inverter(final SolaxSettings settings) {
        SolaxSamples.settings(fixture.settings, settings);
    }

    private void pass() {
        watch.checkOnce().as(StepVerifier::create).verifyComplete();
    }

    //a pass a minute, the settings read again before each as the reader does every five minutes
    private void minutesOf(final int count, final SolaxSettings settings) {
        for (int minute = 0; minute < count; minute++) {
            inverter(settings);
            pass();
            fixture.clock.moveBy(Duration.ofMinutes(1));
        }
    }

    @Test
    void should_warn_once_after_ten_minutes_of_a_min_soc_below_the_reserve_and_store_the_episode() {
        minutesOf(10, LOW_FLOOR);
        verify(fixture.publisher, never()).publishReminder(anyString());

        minutesOf(5, LOW_FLOOR);

        verify(fixture.publisher, times(1)).publishReminder(contains("is held only down to 75 %"));
        assertThat(rows).containsExactly(new SourceAlertEntity(9L, "solax", "reserve", NOW, NOW.plusMinutes(10), null,
            null));
    }

    @Test
    void should_remind_after_an_hour_and_tell_the_return_once_the_owner_raised_the_min_soc() {
        minutesOf(11, LOW_FLOOR);
        minutesOf(60, LOW_FLOOR);
        verify(fixture.publisher, times(2)).publishReminder(anyString());
        verify(fixture.publisher).publishReminder(contains("Still: The battery reserve of 80 %"));

        minutesOf(1, HELD);

        verify(fixture.publisher).publishInfo(contains("The SOLAX holds the battery reserve of 80 % again"));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().closedAt()).isEqualTo(NOW.plusMinutes(71));
    }

    @Test
    void should_warn_of_a_work_mode_changed_outside_the_service() {
        minutesOf(11, SELF_USE);

        verify(fixture.publisher).publishReminder(contains("the work mode of the SOLAX is self use (code 160)"));
    }

    @Test
    void should_never_warn_while_the_inverter_holds_the_reserve_or_no_reserve_is_set() {
        minutesOf(30, HELD);
        fixture.reserveRow.set(ChargeSamples.reserveNeverWritten());
        minutesOf(30, SELF_USE);

        verify(fixture.publisher, never()).publishReminder(anyString());
        verify(alerts, never()).save(any());
    }

    //a charge of a run is the Self Use of the inverter until its end writes the reserve back; a write pending or failed
    //is told by the reserve itself; settings gone stale are not known
    @Test
    void should_not_judge_during_a_charge_before_the_reserve_is_written_or_on_stale_settings() {
        fixture.table.put(1L, ChargeSamples.charging());
        minutesOf(15, SELF_USE);
        fixture.table.clear();
        fixture.reserveRow.set(ChargeSamples.reserveHeld().withWriteOutcome("pending"));
        minutesOf(15, LOW_FLOOR);
        fixture.reserveRow.set(ChargeSamples.reserveHeld().withWriteOutcome("failed"));
        minutesOf(15, LOW_FLOOR);
        fixture.reserveRow.set(ChargeSamples.reserveHeld());
        inverter(LOW_FLOOR);
        for (int minute = 0; minute < 40; minute++) {
            fixture.clock.moveBy(Duration.ofMinutes(1));
            pass();
        }

        verify(fixture.publisher, times(1)).publishReminder(anyString());
        assertThat(rows.getFirst().openedAt()).isEqualTo(NOW.plusMinutes(46));
    }

    //the table follows the broker: a warning that was not published stores nothing and is due again with the next pass
    @Test
    void should_store_nothing_until_the_warning_was_published() {
        when(fixture.publisher.publishReminder(anyString())).thenReturn(Mono.error(new IllegalStateException("broker")));
        minutesOf(12, LOW_FLOOR);
        assertThat(rows).isEmpty();

        when(fixture.publisher.publishReminder(anyString())).thenReturn(Mono.empty());
        minutesOf(1, LOW_FLOOR);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().openedAt()).isEqualTo(NOW);
    }

    //a told row the database did not take is stored first by the next pass - never told twice
    @Test
    void should_store_a_told_row_with_the_next_pass_and_tell_it_once() {
        minutesOf(10, LOW_FLOOR);
        databaseDown.set(true);
        minutesOf(1, LOW_FLOOR);
        assertThat(rows).isEmpty();
        databaseDown.set(false);

        minutesOf(3, LOW_FLOOR);

        verify(fixture.publisher, times(1)).publishReminder(anyString());
        assertThat(rows).hasSize(1);
    }

    @Test
    void should_complete_a_pass_whose_read_failed() {
        when(fixture.reserves.findById(1)).thenReturn(Mono.error(new IllegalStateException("database down")));

        watch.check().as(StepVerifier::create).verifyComplete();

        verify(fixture.publisher, never()).publishReminder(anyString());
    }

    //GET /battery/reserve (HAS-518): what the inverter has beside what the service set
    @Test
    void should_answer_the_reserve_with_the_settings_of_the_inverter_and_the_floor_below_the_level() {
        inverter(LOW_FLOOR);

        fixture.reserve.settings().as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.inverter()).isEqualTo(new InverterSettingsReply("back up", 75, 80, NOW));
                assertThat(reply.floorBelowLevel()).isTrue();
            })
            .verifyComplete();
    }

    @Test
    void should_answer_the_reserve_without_the_inverter_while_its_settings_are_not_known() {
        fixture.reserve.settings().as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.inverter()).isNull();
                assertThat(reply.floorBelowLevel()).isNull();
            })
            .verifyComplete();

        fixture.reserveRow.set(ChargeSamples.reserveNeverWritten());

        fixture.reserve.settings().map(ReserveReply::floorBelowLevel).as(StepVerifier::create)
            .expectNext(false)
            .verifyComplete();
    }

    //every write of the service to the inverter is noted, so that its settings are read again a moment later
    @Test
    void should_note_a_write_of_the_service_for_the_reader_of_the_settings() {
        fixture.reserveRow.set(ChargeSamples.reserveHeld().withWriteOutcome("pending"));
        assertThat(fixture.settings.wroteAt()).isEmpty();

        fixture.commands.serially(fixture.reserve.pass()).as(StepVerifier::create).verifyComplete();

        assertThat(fixture.settings.wroteAt()).contains(fixture.clock.instant());
    }

    @Test
    void should_be_bounded_below_its_delay() {
        assertThat(ReserveFloorWatch.LIMIT).isLessThan(Duration.ofMinutes(1));
    }
}

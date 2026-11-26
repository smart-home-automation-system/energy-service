package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.api.model.ReserveReply;
import cloud.cholewa.energy.database.GridOutageEntity;
import cloud.cholewa.energy.database.GridOutageRepository;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
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

//the watch of a battery below its reserve (HAS-495) on its collaborators - the reserve of the service, the battery, the
//charges, the outages of the grid, the table and the broker - with the clock moved on, and what GET /battery/reserve
//answers of the battery
class ReserveSocWatchTest {

    private final ChargeFixture fixture = new ChargeFixture();
    private final GridOutageRepository outages = mock(GridOutageRepository.class);
    private final SourceAlertRepository alerts = mock(SourceAlertRepository.class);
    private final ReserveSocWatch watch = new ReserveSocWatch(fixture.reserve, fixture.repository, outages,
        fixture.batteryService, alerts, fixture.publisher, ChargeSamples.SOC, fixture.clock);
    private final List<SourceAlertEntity> rows = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(8);
    private final AtomicBoolean databaseDown = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        fixture.reserveRow.set(ChargeSamples.reserveHeld());
        when(outages.findFirstByEndedAtIsNull()).thenReturn(Mono.empty());
        when(fixture.publisher.publishReminder(anyString())).thenReturn(Mono.empty());
        when(alerts.findByClosedAtIsNullAndSourceAndKind("battery", "reserve")).thenAnswer(call -> Flux.fromIterable(
            rows.stream().filter(row -> "battery".equals(row.source()) && row.closedAt() == null).toList()));
        when(alerts.findByClosedAtIsNullAndSourceAndKind("solax", "reserve")).thenReturn(Flux.empty());
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

    private void pass() {
        watch.checkOnce().as(StepVerifier::create).verifyComplete();
    }

    //a pass a minute with the battery at soc - idle unless power says it charges (W, positive charges)
    private void minutesOf(final int count, final double soc, final double power) {
        fixture.battery(soc, power, false);
        for (int minute = 0; minute < count; minute++) {
            pass();
            fixture.clock.moveBy(Duration.ofMinutes(1));
        }
    }

    private void minutesOf(final int count, final double soc) {
        minutesOf(count, soc, 0.0);
    }

    @Test
    void should_warn_once_after_half_an_hour_below_the_reserve_and_store_the_episode() {
        minutesOf(30, 62.0);
        verify(fixture.publisher, never()).publishReminder(anyString());

        minutesOf(5, 62.0);

        verify(fixture.publisher, times(1)).publishReminder(contains("the battery is at 62 % and not charging"));
        assertThat(rows).containsExactly(new SourceAlertEntity(9L, "battery", "reserve", NOW, NOW.plusMinutes(30),
            null, null));
    }

    @Test
    void should_remind_after_an_hour_and_tell_the_return_once_the_battery_is_back() {
        minutesOf(31, 62.0);
        minutesOf(60, 62.0);
        verify(fixture.publisher, times(2)).publishReminder(anyString());
        verify(fixture.publisher).publishReminder(contains("Still: The battery reserve of 80 %"));

        minutesOf(1, 76.0);

        verify(fixture.publisher).publishInfo(contains("The battery is held at the reserve of 80 % again: 76 %"));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().closedAt()).isEqualTo(NOW.plusMinutes(91));
    }

    //the reserve switched on below its level: the inverter charges the battery up from the grid - never a warning
    @Test
    void should_never_warn_while_the_battery_charges_up_to_the_reserve_or_holds_it() {
        minutesOf(180, 40.0, 4800.0);
        minutesOf(60, 79.0);

        verify(fixture.publisher, never()).publishReminder(anyString());
        verify(alerts, never()).save(any());
    }

    //in EPS the drop is the purpose; a charge open is the Self Use of the inverter; a write pending or failed is told by
    //the reserve itself; a battery not known or stale says nothing
    @Test
    void should_not_judge_an_outage_a_charge_a_write_not_taken_or_a_stale_battery() {
        when(outages.findFirstByEndedAtIsNull()).thenReturn(Mono.just(new GridOutageEntity(1L, NOW, null, 90.0, null,
            90.0, null, null)));
        minutesOf(40, 30.0);
        when(outages.findFirstByEndedAtIsNull()).thenReturn(Mono.empty());
        fixture.table.put(1L, ChargeSamples.charging());
        minutesOf(40, 30.0);
        fixture.table.clear();
        fixture.reserveRow.set(ChargeSamples.reserveHeld().withWriteOutcome("pending"));
        minutesOf(40, 30.0);
        fixture.reserveRow.set(ChargeSamples.reserveHeld());
        fixture.battery(30.0, 0.0, true);
        for (int minute = 0; minute < 40; minute++) {
            pass();
            fixture.clock.moveBy(Duration.ofMinutes(1));
        }
        verify(fixture.publisher, never()).publishReminder(anyString());

        minutesOf(31, 30.0);

        verify(fixture.publisher, times(1)).publishReminder(anyString());
        assertThat(rows.getFirst().openedAt()).isEqualTo(NOW.plusMinutes(160));
    }

    //an episode open while the grid goes out stays open and is not reminded; switching the reserve off ends it
    @Test
    void should_keep_an_episode_through_an_outage_and_end_it_once_the_reserve_is_switched_off() {
        minutesOf(31, 62.0);
        when(outages.findFirstByEndedAtIsNull()).thenReturn(Mono.just(new GridOutageEntity(1L, NOW, null, 62.0, null,
            62.0, null, null)));
        minutesOf(120, 20.0);
        verify(fixture.publisher, times(1)).publishReminder(anyString());

        fixture.reserveRow.set(ChargeSamples.reserveNeverWritten());
        minutesOf(1, 20.0);

        verify(fixture.publisher).publishInfo(contains("The battery reserve is switched off"));
        assertThat(rows.getFirst().closedAt()).isEqualTo(NOW.plusMinutes(151));
    }

    //the floor of the inverter below the reserve already told (HAS-518): the battery below it is the same cause
    @Test
    void should_not_warn_again_while_the_floor_of_the_inverter_is_told() {
        rows.add(new SourceAlertEntity(3L, "solax", "reserve", NOW.minusHours(1), NOW.minusHours(1), null, null));
        when(alerts.findByClosedAtIsNullAndSourceAndKind("solax", "reserve")).thenAnswer(call -> Flux.fromIterable(
            rows.stream().filter(row -> "solax".equals(row.source()) && row.closedAt() == null).toList()));
        minutesOf(60, 60.0);
        verify(fixture.publisher, never()).publishReminder(anyString());

        rows.replaceAll(row -> "solax".equals(row.source()) ? new SourceAlertEntity(row.id(), row.source(), row.kind(),
            row.openedAt(), row.lastToldAt(), NOW.plusMinutes(60), null) : row);
        minutesOf(31, 60.0);

        verify(fixture.publisher, times(1)).publishReminder(contains("the battery is at 60 %"));
    }

    //the table follows the broker: a warning that was not published stores nothing and is due again with the next pass
    @Test
    void should_store_nothing_until_the_warning_was_published() {
        when(fixture.publisher.publishReminder(anyString())).thenReturn(Mono.error(new IllegalStateException("broker")));
        minutesOf(32, 62.0);
        assertThat(rows).isEmpty();

        when(fixture.publisher.publishReminder(anyString())).thenReturn(Mono.empty());
        minutesOf(1, 62.0);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().openedAt()).isEqualTo(NOW);
    }

    //a told row the database did not take is stored first by the next pass - never told twice
    @Test
    void should_store_a_told_row_with_the_next_pass_and_tell_it_once() {
        minutesOf(30, 62.0);
        databaseDown.set(true);
        minutesOf(1, 62.0);
        assertThat(rows).isEmpty();
        databaseDown.set(false);

        minutesOf(3, 62.0);

        verify(fixture.publisher, times(1)).publishReminder(anyString());
        assertThat(rows).hasSize(1);
    }

    @Test
    void should_complete_a_pass_whose_read_failed() {
        when(fixture.reserves.findById(1)).thenReturn(Mono.error(new IllegalStateException("database down")));

        watch.check().as(StepVerifier::create).verifyComplete();

        verify(fixture.publisher, never()).publishReminder(anyString());
    }

    //GET /battery/reserve (HAS-495): whether the battery itself is at the reserve
    @Test
    void should_answer_whether_the_battery_holds_the_reserve() {
        fixture.battery(76.0, false);
        fixture.reserve.settings().map(ReserveReply::held).as(StepVerifier::create).expectNext(true).verifyComplete();

        fixture.battery(74.0, false);
        fixture.reserve.settings().map(ReserveReply::held).as(StepVerifier::create).expectNext(false).verifyComplete();
    }

    @Test
    void should_leave_held_out_without_a_reserve_or_a_known_battery() {
        fixture.battery(40.0, true);
        fixture.reserve.settings().as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.held()).isNull())
            .verifyComplete();

        fixture.battery(40.0, false);
        fixture.reserveRow.set(ChargeSamples.reserveNeverWritten());
        fixture.reserve.settings().as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.held()).isNull())
            .verifyComplete();
    }

    @Test
    void should_answer_the_reserve_whose_battery_could_not_be_read() {
        when(fixture.batteryService.battery()).thenReturn(Mono.error(new IllegalStateException("dongle")));

        fixture.reserve.settings().as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.enabled()).isTrue();
                assertThat(reply.held()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void should_be_bounded_below_its_delay() {
        assertThat(Duration.ofMinutes(1)).isGreaterThan(ReserveSocWatch.LIMIT);
    }
}

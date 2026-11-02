package cloud.cholewa.energy.flows;

import cloud.cholewa.energy.api.model.BatteryReply;
import cloud.cholewa.energy.api.model.FlowsReply;
import cloud.cholewa.energy.api.model.GridReply;
import cloud.cholewa.energy.api.model.InverterReply;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.history.StoredValue;
import cloud.cholewa.energy.history.StoredValues;
import cloud.cholewa.energy.inverter.BatteryService;
import cloud.cholewa.energy.inverter.InverterService;
import cloud.cholewa.energy.meter.GridService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the balance of the house now (HAS-331), with the sign convention of the house: the grid positive while the house takes
//from it, the battery positive while it charges - the house load is the production plus the grid minus the battery
class FlowsServiceTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 12, 0);
    private static final LocalDateTime MIDNIGHT = NOW.toLocalDate().atStartOfDay();

    private final GridService gridService = mock(GridService.class);
    private final InverterService inverterService = mock(InverterService.class);
    private final BatteryService batteryService = mock(BatteryService.class);
    private final StoredValues storedValues = mock(StoredValues.class);
    private final FlowsService service = new FlowsService(gridService, inverterService, batteryService, storedValues,
        Clock.fixed(NOW.atZone(HOUSE).toInstant(), HOUSE));

    private static InverterReply inverter(final String id, final Double pv, final Double dailyYield, final boolean stale) {
        return new InverterReply(id, 1L, id, "garage", null, null, null, null, null, pv, null, null, null, null, null,
            dailyYield, null, NOW, stale, "local");
    }

    private static GridReply grid(final Double power, final boolean stale) {
        return new GridReply(null, power, 891.2, 5390.0, null, NOW, stale);
    }

    private static BatteryReply battery(final Double power, final boolean stale) {
        return new BatteryReply(50.0, 99.0, power, null, null, null, null, null, null, 1.0, 0.2, NOW, stale, "local");
    }

    @BeforeEach
    void setUp() {
        when(gridService.grid()).thenReturn(Mono.just(grid(-1500.0, false)));
        when(inverterService.inverter("solax")).thenReturn(Mono.just(inverter("solax", 3000.0, 4.0, false)));
        when(inverterService.inverter("sofar")).thenReturn(Mono.just(inverter("sofar", 1000.0, 1.0, false)));
        when(batteryService.battery()).thenReturn(Mono.just(battery(800.0, false)));
        when(storedValues.lastBefore(StoredValue.GRID_IMPORT, MIDNIGHT)).thenReturn(Mono.just(new BigDecimal("880.00")));
        when(storedValues.lastBefore(StoredValue.GRID_EXPORT, MIDNIGHT)).thenReturn(Mono.just(new BigDecimal("5388.50")));
    }

    //4 kW from the panels, 1.5 kW returned to the grid, 0.8 kW into the battery: the house uses 1.7 kW
    @Test
    void should_balance_the_house_now() {
        service.flows().as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.production()).isEqualTo(4000.0);
                assertThat(reply.gridPower()).isEqualTo(-1500.0);
                assertThat(reply.batteryPower()).isEqualTo(800.0);
                assertThat(reply.houseLoad()).isEqualTo(1700.0);
                assertThat(reply.inputs()).containsOnlyKeys("grid", "solax", "sofar", "battery");
                assertThat(reply.inputs()).containsEntry("sofar", new FlowsReply.Input(NOW, false));
            })
            .verifyComplete();
    }

    //today: 5 kWh produced, 11.2 imported and 1.5 exported since midnight, 1 charged and 0.2 discharged
    @Test
    void should_answer_the_day_so_far() {
        service.flows().as(StepVerifier::create)
            .assertNext(reply -> {
                final FlowsReply.Today today = reply.today();
                assertThat(today.production()).isEqualByComparingTo("5.0");
                assertThat(today.imported()).isEqualByComparingTo("11.20");
                assertThat(today.exported()).isEqualByComparingTo("1.50");
                assertThat(today.houseLoad()).isEqualByComparingTo("13.90");
                assertThat(today.selfConsumption()).isEqualByComparingTo("0.700");
                assertThat(today.gridShare()).isEqualByComparingTo("0.806");
            })
            .verifyComplete();
    }

    //a stale input is not known: what depends on it is left out, never 0
    @Test
    void should_leave_out_what_depends_on_a_stale_input() {
        when(inverterService.inverter("sofar")).thenReturn(Mono.just(inverter("sofar", 1000.0, 1.0, true)));

        service.flows().as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.solaxPv()).isEqualTo(3000.0);
                assertThat(reply.sofarPv()).isNull();
                assertThat(reply.production()).isNull();
                assertThat(reply.houseLoad()).isNull();
                assertThat(reply.today().production()).isNull();
                assertThat(reply.today().imported()).isEqualByComparingTo("11.20");
                assertThat(reply.inputs().get("sofar").stale()).isTrue();
            })
            .verifyComplete();
    }

    //an inverter not registered, or the registry not known yet, is an input not known - never an error
    @Test
    void should_answer_without_an_inverter_not_registered() {
        when(inverterService.inverter("sofar")).thenReturn(Mono.error(EnergyException.inverterNotFound("sofar")));

        service.flows().as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.production()).isNull();
                assertThat(reply.inputs()).containsEntry("sofar", new FlowsReply.Input(null, true));
            })
            .verifyComplete();
    }

    //the counters at midnight are read once a day
    @Test
    void should_read_the_counters_at_midnight_once_a_day() {
        service.flows().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        service.flows().as(StepVerifier::create).expectNextCount(1).verifyComplete();

        verify(storedValues, times(1)).lastBefore(StoredValue.GRID_IMPORT, MIDNIGHT);
    }

    //right after midnight the last row of the day before may still come: the counters are read again, not held
    @Test
    void should_not_hold_the_counters_right_after_midnight() {
        final Clock early = Clock.fixed(MIDNIGHT.plusMinutes(2).atZone(HOUSE).toInstant(), HOUSE);
        final FlowsService justAfterMidnight = new FlowsService(gridService, inverterService, batteryService,
            storedValues, early);

        justAfterMidnight.flows().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        justAfterMidnight.flows().as(StepVerifier::create).expectNextCount(1).verifyComplete();

        verify(storedValues, times(2)).lastBefore(StoredValue.GRID_IMPORT, MIDNIGHT);
    }

    //without a reading of the meter before midnight (or a database that fails) imported and exported are left out
    @Test
    void should_leave_out_the_grid_of_today_without_its_counters_at_midnight() {
        when(storedValues.lastBefore(any(), any())).thenReturn(Mono.error(new IllegalStateException("database down")));

        service.flows().as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.today().imported()).isNull();
                assertThat(reply.today().exported()).isNull();
                assertThat(reply.today().production()).isEqualByComparingTo("5.0");
                assertThat(reply.houseLoad()).isEqualTo(1700.0);
            })
            .verifyComplete();
    }
}

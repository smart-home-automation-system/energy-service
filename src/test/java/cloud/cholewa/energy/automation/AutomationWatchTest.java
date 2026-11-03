package cloud.cholewa.energy.automation;

import cloud.cholewa.energy.charge.ChargeTestKit;
import cloud.cholewa.energy.database.BatteryChargeRunEntity;
import cloud.cholewa.energy.database.EnergyAutomationEntity;
import cloud.cholewa.energy.database.EnergyAutomationRepository;
import cloud.cholewa.energy.database.PlannedOutageEntity;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.solaxcloud.SolaxCloudException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the automatic charge before a planned outage (HAS-338) - the dry run of the DoD: a fake outage, a stubbed SolaX cloud and
//a clock moved on; the start computed every pass and the command sent once
class AutomationWatchTest {

    //the outage of the street at 20:00 on the day of the samples (14:05:30), 45 % to 100 % at 5 kW plus an hour - 16:05
    private static final LocalDateTime OUTAGE_START = LocalDateTime.of(2026, 11, 3, 20, 0);
    private static final PlannedOutageEntity OUTAGE = new PlannedOutageEntity(3L, "o-1", "planned", "street", OUTAGE_START,
        OUTAGE_START.plusHours(3), "Testowo ul. Przykładowa", ChargeTestKit.NOW, ChargeTestKit.NOW, null, null,
        ChargeTestKit.NOW, null, null, null, null, false, null);

    private final ChargeTestKit kit = new ChargeTestKit();
    private final EnergyAutomationRepository settings = mock(EnergyAutomationRepository.class);
    private final AutomationPlan plan = kit.plan();
    private final AutomationWatch watch = new AutomationWatch(new AutomationService(settings, kit.clock()), kit.outages(),
        kit.repository(), kit.service(), plan, kit.publisher(), kit.outageLane(),
        new SolaxProperties(Duration.ofSeconds(15), Duration.ofMinutes(1), 26.5), kit.clock());

    @BeforeEach
    void setUp() {
        switchOn(true);
        when(kit.outages().findFirstByClosedAtIsNullAndKindAndScopeAndSkipAutomationFalseAndStartsAtAfterOrderByStartsAt(
            eq("planned"), eq("street"), any())).thenReturn(Mono.just(OUTAGE));
        when(kit.outages().setChargeRun(any(), any())).thenReturn(Mono.just(1));
        when(kit.publisher().publishInfoSms(anyString())).thenReturn(Mono.empty());
        when(kit.publisher().publishAlert(anyString())).thenReturn(Mono.empty());
    }

    private void switchOn(final boolean on) {
        when(settings.findById(EnergyAutomationEntity.ROW_ID)).thenReturn(Mono.just(
            new EnergyAutomationEntity(1, on, 100, 5.0, 60, null)));
    }

    private void pass() {
        watch.pass().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_plan_the_start_and_wait_for_it() {
        pass();

        assertThat(plan.current()).contains(new AutomationPlan.Planned(3L, OUTAGE_START,
            LocalDateTime.of(2026, 11, 3, 16, 5)));
        verify(kit.client(), never()).setSelfUse(any());
    }

    @Test
    void should_start_the_charge_at_the_planned_start_once() {
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 5));
        pass();
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 6));
        pass();
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 17, 30));
        pass();

        verify(kit.client(), times(1)).setSelfUse(any());
        final BatteryChargeRunEntity run = kit.only();
        assertThat(run.source()).isEqualTo("automation");
        assertThat(run.plannedOutageId()).isEqualTo(3L);
        assertThat(run.windowEnd()).isEqualTo(OUTAGE_START);
        verify(kit.outages()).setChargeRun(3L, run.id());
        verify(kit.publisher()).publishInfoSms("Battery charging from the grid started before the planned outage of"
            + " Tue 3 Nov 20:00-23:00: SOC 45 % to 100 %.");
    }

    //a window never started twice for one outage - a run that ended, cancelled or failed, included
    @Test
    void should_never_start_a_second_run_for_one_outage() {
        kit.table().put(1L, kit.ended().withPlannedOutageId(3L));
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 17, 0));

        pass();

        verify(kit.client(), never()).setSelfUse(any());
    }

    @Test
    void should_start_nothing_with_the_switch_off() {
        switchOn(false);
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 17, 0));

        pass();

        verify(kit.client(), never()).setSelfUse(any());
        assertThat(plan.current()).isPresent();
    }

    //a manual run is left alone, never stopped, nothing told
    @Test
    void should_leave_a_manual_run_alone() {
        kit.table().put(1L, kit.charging());
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 17, 0));

        pass();

        verify(kit.client(), never()).setSelfUse(any());
        verify(kit.publisher(), never()).publishAlert(anyString());
    }

    @Test
    void should_tell_once_that_the_soc_is_not_known() {
        kit.battery(null, true);
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 17, 0));

        pass();
        pass();

        verify(kit.publisher(), times(1)).publishAlert("The automatic charge before the planned outage of Tue 3 Nov"
            + " 20:00-23:00 waits: the state of charge of the battery is not known.");
        verify(kit.client(), never()).setSelfUse(any());
    }

    //a start the cloud refused is told once and not tried again - its failed run is linked to the outage
    @Test
    void should_tell_once_a_start_the_cloud_refused() {
        when(kit.client().setSelfUse(any())).thenReturn(Mono.error(new SolaxCloudException("The SolaX cloud answered code 10500")));
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 5));
        pass();
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 7));
        pass();

        verify(kit.client(), times(1)).setSelfUse(any());
        verify(kit.publisher(), times(1)).publishAlert("The automatic charge before the planned outage of Tue 3 Nov"
            + " 20:00-23:00 did not start: The SolaX cloud refused the command.");
        assertThat(kit.only().failure()).isEqualTo("DEVICE_FAILED");
    }

    @Test
    void should_clear_the_plan_without_an_outage() {
        when(kit.outages().findFirstByClosedAtIsNullAndKindAndScopeAndSkipAutomationFalseAndStartsAtAfterOrderByStartsAt(
            eq("planned"), eq("street"), any())).thenReturn(Mono.empty());

        pass();

        assertThat(plan.current()).isEmpty();
    }
}

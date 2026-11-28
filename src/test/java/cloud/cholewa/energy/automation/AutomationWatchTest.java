package cloud.cholewa.energy.automation;

import cloud.cholewa.energy.charge.ChargeTestKit;
import cloud.cholewa.energy.database.BatteryChargeRunEntity;
import cloud.cholewa.energy.database.EnergyAutomationEntity;
import cloud.cholewa.energy.database.EnergyAutomationRepository;
import cloud.cholewa.energy.database.PlannedOutageEntity;
import cloud.cholewa.energy.frequency.FrequencyState;
import cloud.cholewa.energy.shedding.LoadSheddingWatch;
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
import static org.mockito.Mockito.atLeastOnce;
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
    private final FrequencyState frequencyState = mock(FrequencyState.class);
    private final AutomationWatch watch = watch();

    //three runs an outage at most, five minutes after a failed one
    private AutomationWatch watch() {
        return new AutomationWatch(new AutomationService(settings, mock(LoadSheddingWatch.class), kit.clock()), kit.outages(), kit.repository(),
            kit.service(), plan, kit.publisher(), kit.outageLane(),
            new SolaxProperties(Duration.ofSeconds(15), Duration.ofMinutes(1), 26.5), frequencyState,
            new AutomationProperties(3, Duration.ofMinutes(5)), kit.clock());
    }

    //a run of the outage that failed and set nothing back, as ChargeWatch or a refused command leaves it
    private BatteryChargeRunEntity failed(final LocalDateTime endedAt) {
        return kit.charging().withPlannedOutageId(3L).withRequestedAt(endedAt.minusMinutes(2)).withEndedAt(endedAt)
            .withEndedBy("failed").withFailure("DEVICE_FAILED").withRestoredAt(endedAt);
    }

    @BeforeEach
    void setUp() {
        switchOn(true);
        when(kit.outages().findFirstByClosedAtIsNullAndKindAndScopeAndSkipAutomationFalseAndStartsAtAfterOrderByStartsAtAscIdAsc(
            eq("planned"), eq("street"), any())).thenReturn(Mono.just(OUTAGE));
        when(kit.outages().setChargeRun(any(), any())).thenReturn(Mono.just(1));
        when(kit.publisher().publishInfoSms(anyString())).thenReturn(Mono.empty());
        when(kit.publisher().publishAlert(anyString())).thenReturn(Mono.empty());
        when(kit.publisher().publishAlertSms(anyString())).thenReturn(Mono.empty());
    }

    private void switchOn(final boolean on) {
        when(settings.findById(EnergyAutomationEntity.ROW_ID)).thenReturn(Mono.just(
            new EnergyAutomationEntity(1, on, 100, 5.0, 60, null, true)));
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
        verify(kit.outages(), atLeastOnce()).setChargeRun(3L, run.id());
        verify(kit.publisher()).publishInfoSms("Battery charging from the grid started before the planned outage of"
            + " Tue 3 Nov 20:00-23:00: SOC 45 % to 100 %.");
    }

    //the grid frequency off its band (HAS-503 - a generator): the inverter does not connect, so the start waits for it
    //and comes once the frequency is back, before the outage
    @Test
    void should_wait_with_the_start_while_the_grid_frequency_is_off() {
        when(frequencyState.off()).thenReturn(true);
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 5));
        pass();
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 6));
        pass();
        verify(kit.client(), never()).setSelfUse(any());
        verify(kit.publisher(), never()).publishAlert(anyString());

        when(frequencyState.off()).thenReturn(false);
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 17, 0));
        pass();

        verify(kit.client(), times(1)).setSelfUse(any());
        assertThat(kit.only().plannedOutageId()).isEqualTo(3L);
    }

    //a window never started twice for one outage once a run of it ended by its target or its window
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

        verify(kit.publisher(), times(1)).publishAlertSms("The automatic charge before the planned outage of Tue 3 Nov"
            + " 20:00-23:00 waits: the state of charge of the battery is not known.");
        verify(kit.client(), never()).setSelfUse(any());
    }

    private static final String FAILED_TEXT = "The automatic charge before the planned outage of Tue 3 Nov 20:00-23:00"
        + " failed (DEVICE_FAILED) - a failed charge is tried again after 5 min, at most 3 runs an outage.";
    private static final String GIVEN_UP_TEXT = "The automatic charge before the planned outage of Tue 3 Nov 20:00-23:00"
        + " is given up after 3 attempts - the battery is not charged, charge it by hand.";

    //HAS-478: a start the cloud refused leaves a failed run - texted once with the next pass, tried again after the pause
    @Test
    void should_text_a_start_the_cloud_refused_once_and_try_again_after_the_pause() {
        when(kit.client().setSelfUse(any()))
            .thenReturn(Mono.error(new SolaxCloudException("The SolaX cloud answered code 10500")))
            .thenReturn(Mono.just("r-2"));
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 5));
        pass();
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 6));
        pass();
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 9));
        pass();

        verify(kit.client(), times(1)).setSelfUse(any());
        verify(kit.publisher(), times(1)).publishAlertSms(FAILED_TEXT);

        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 10));
        pass();
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 11));
        pass();

        verify(kit.client(), times(2)).setSelfUse(any());
        verify(kit.publisher(), times(1)).publishAlertSms(anyString());
        verify(kit.publisher(), times(1)).publishInfoSms(anyString());
        //the refused run is linked on the outage by the pass after it - the start itself could not
        verify(kit.outages(), atLeastOnce()).setChargeRun(3L, 1L);
        assertThat(kit.table().values()).extracting(BatteryChargeRunEntity::endedBy).containsExactly("failed", null);
        assertThat(kit.table().values()).extracting(BatteryChargeRunEntity::plannedOutageId).containsOnly(3L);
    }

    //a charge the inverter was not seen to take is ended as failed by the charge watch - tried again as well
    @Test
    void should_try_again_a_charge_that_was_not_seen() {
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 0));
        pass();
        kit.table().put(101L, failed(LocalDateTime.of(2026, 11, 3, 16, 20)).withId(101L));
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 21));
        pass();

        verify(kit.client(), never()).setSelfUse(any());
        verify(kit.publisher(), times(1)).publishAlertSms(FAILED_TEXT);

        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 25));
        pass();

        verify(kit.client(), times(1)).setSelfUse(any());
    }

    //the whole of it as it happens: the first refusal texted, the second not, the third the give-up - two texts, three
    //runs, and nothing more however long the outage is still ahead
    @Test
    void should_text_the_first_failure_and_the_give_up_of_three_refused_starts() {
        when(kit.client().setSelfUse(any()))
            .thenReturn(Mono.error(new SolaxCloudException("The SolaX cloud answered code 10500")));
        for (final int minute : new int[] {5, 6, 10, 11, 15, 16, 21, 45}) {
            kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, minute));
            pass();
        }

        verify(kit.client(), times(3)).setSelfUse(any());
        verify(kit.publisher(), times(1)).publishAlertSms(FAILED_TEXT);
        verify(kit.publisher(), times(1)).publishAlertSms(GIVEN_UP_TEXT);
        verify(kit.publisher(), times(2)).publishAlertSms(anyString());
        assertThat(kit.table().values()).extracting(BatteryChargeRunEntity::endedBy)
            .containsExactly("failed", "failed", "failed");
    }

    //HAS-477 with HAS-478: the row goes on under a twin whose three runs failed - they are the runs of this outage
    //(found through the run the row carries), so no fourth run and no text again
    @Test
    void should_count_the_failed_runs_of_a_twin_row_as_the_runs_of_the_outage() {
        when(kit.outages().findFirstByClosedAtIsNullAndKindAndScopeAndSkipAutomationFalseAndStartsAtAfterOrderByStartsAtAscIdAsc(
            eq("planned"), eq("street"), any())).thenReturn(Mono.just(OUTAGE.withChargeRunId(103L)));
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 0));
        pass();
        kit.table().put(101L, failed(LocalDateTime.of(2026, 11, 3, 16, 10)).withId(101L).withPlannedOutageId(9L));
        kit.table().put(102L, failed(LocalDateTime.of(2026, 11, 3, 16, 17)).withId(102L).withPlannedOutageId(9L));
        kit.table().put(103L, failed(LocalDateTime.of(2026, 11, 3, 16, 24)).withId(103L).withPlannedOutageId(9L));
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 25));
        pass();
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 17, 0));
        pass();

        verify(kit.client(), never()).setSelfUse(any());
        verify(kit.publisher(), times(1)).publishAlertSms(GIVEN_UP_TEXT);
        verify(kit.publisher(), times(1)).publishAlertSms(anyString());
    }

    //the row carries one failed run of its twin and then gets runs of its own: the twin's run stays the first of the
    //outage - taken over by the row -, so two more runs make three, the give-up counts three, and the failure the pod
    //before told is not texted again
    @Test
    void should_keep_the_run_of_a_twin_row_once_the_row_has_runs_of_its_own() {
        when(kit.outages().findFirstByClosedAtIsNullAndKindAndScopeAndSkipAutomationFalseAndStartsAtAfterOrderByStartsAtAscIdAsc(
            eq("planned"), eq("street"), any())).thenReturn(Mono.just(OUTAGE.withChargeRunId(101L)));
        when(kit.client().setSelfUse(any()))
            .thenReturn(Mono.error(new SolaxCloudException("The SolaX cloud answered code 10500")));
        kit.table().put(101L, failed(LocalDateTime.of(2026, 11, 3, 15, 58)).withId(101L).withPlannedOutageId(9L));
        for (final int minute : new int[] {5, 6, 10, 11, 16, 30}) {
            kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, minute));
            pass();
        }

        verify(kit.client(), times(2)).setSelfUse(any());
        assertThat(kit.table().values()).extracting(BatteryChargeRunEntity::plannedOutageId).containsOnly(3L);
        verify(kit.publisher(), times(1)).publishAlertSms(GIVEN_UP_TEXT);
        verify(kit.publisher(), times(1)).publishAlertSms(anyString());
    }

    //a failed run whose baseline is not set back yet: the next attempt is due but waits for it - no run, no text of a
    //refused start
    @Test
    void should_wait_with_the_next_attempt_while_the_failed_run_is_still_set_back() {
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 0));
        pass();
        kit.table().put(101L, failed(LocalDateTime.of(2026, 11, 3, 16, 10)).withId(101L).withRestoredAt(null));
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 20));
        pass();
        pass();

        verify(kit.client(), never()).setSelfUse(any());
        verify(kit.publisher(), times(1)).publishAlertSms(FAILED_TEXT);
        verify(kit.publisher(), times(1)).publishAlertSms(anyString());
    }

    //the charge watch runs before the first pass of this watch: a run it ends as failed right after the pod start is
    //a failure of this pod, and is texted
    @Test
    void should_text_a_failure_between_the_pod_start_and_the_first_pass() {
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 10));
        final AutomationWatch started = watch();
        started.started();
        kit.table().put(101L, failed(LocalDateTime.of(2026, 11, 3, 16, 10, 30)).withId(101L));
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 10, 45));

        started.pass().as(StepVerifier::create).verifyComplete();

        verify(kit.publisher(), times(1)).publishAlertSms(FAILED_TEXT);
    }

    //the SOC not known weeks or hours before even an empty battery would have to start is nobody's SMS: 0 % to 100 %
    //of 26.5 kWh at 5 kW is 318 min, plus the hour - 13:42 for the outage of 20:00
    @Test
    void should_not_text_the_soc_not_known_before_the_latest_planned_start() {
        kit.battery(null, true);
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 13, 41));
        pass();

        verify(kit.publisher(), never()).publishAlertSms(anyString());

        kit.moveTo(LocalDateTime.of(2026, 11, 3, 13, 42));
        pass();

        verify(kit.publisher(), times(1)).publishAlertSms(anyString());
    }

    //the attempts used up: one text more, no run more - whatever the time
    @Test
    void should_give_up_after_three_failed_runs_with_one_text() {
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 0));
        pass();
        kit.table().put(101L, failed(LocalDateTime.of(2026, 11, 3, 16, 10)).withId(101L));
        kit.table().put(102L, failed(LocalDateTime.of(2026, 11, 3, 16, 17)).withId(102L));
        kit.table().put(103L, failed(LocalDateTime.of(2026, 11, 3, 16, 24)).withId(103L));
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 25));
        pass();
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 17, 30));
        pass();

        verify(kit.client(), never()).setSelfUse(any());
        verify(kit.publisher(), times(1)).publishAlertSms(GIVEN_UP_TEXT);
        verify(kit.publisher(), times(1)).publishAlertSms(anyString());
    }

    //a cancel is the owner's word: never another run, whatever failed before it
    @Test
    void should_never_try_again_after_a_cancelled_run() {
        kit.table().put(101L, failed(LocalDateTime.of(2026, 11, 3, 16, 10)).withId(101L));
        kit.table().put(102L, failed(LocalDateTime.of(2026, 11, 3, 16, 20)).withId(102L).withEndedBy("cancel")
            .withFailure(null));
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 17, 0));

        pass();

        verify(kit.client(), never()).setSelfUse(any());
    }

    //a restart between the attempts: the failure of before is told by the pod before - no text again - and the next
    //attempt comes at its time, counted from the stored runs
    @Test
    void should_text_nothing_again_after_a_restart_and_go_on_with_the_attempts() {
        kit.table().put(101L, failed(LocalDateTime.of(2026, 11, 3, 16, 10)).withId(101L));
        final AutomationWatch restarted = watch();
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 12));
        restarted.pass().as(StepVerifier::create).verifyComplete();

        verify(kit.client(), never()).setSelfUse(any());

        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 15));
        restarted.pass().as(StepVerifier::create).verifyComplete();

        verify(kit.client(), times(1)).setSelfUse(any());
        verify(kit.publisher(), never()).publishAlertSms(anyString());
    }

    //a give-up of before a restart is not texted again either
    @Test
    void should_not_text_a_give_up_of_before_a_restart_again() {
        kit.table().put(101L, failed(LocalDateTime.of(2026, 11, 3, 16, 10)).withId(101L));
        kit.table().put(102L, failed(LocalDateTime.of(2026, 11, 3, 16, 17)).withId(102L));
        kit.table().put(103L, failed(LocalDateTime.of(2026, 11, 3, 16, 24)).withId(103L));
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 30));

        watch().pass().as(StepVerifier::create).verifyComplete();

        verify(kit.publisher(), never()).publishAlertSms(anyString());
        verify(kit.client(), never()).setSelfUse(any());
    }

    //a start refused before a run was made (here: the registry of the inverters not read) is texted once and tried
    //with every pass - it used no attempt
    @Test
    void should_text_once_a_start_refused_before_a_run_was_made() {
        kit.registryNotRead();
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 5));
        pass();
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 16, 7));
        pass();

        verify(kit.publisher(), times(1)).publishAlertSms(anyString());
        verify(kit.publisher(), never()).publishAlert(anyString());
        assertThat(kit.table()).isEmpty();
    }

    @Test
    void should_clear_the_plan_without_an_outage() {
        when(kit.outages().findFirstByClosedAtIsNullAndKindAndScopeAndSkipAutomationFalseAndStartsAtAfterOrderByStartsAtAscIdAsc(
            eq("planned"), eq("street"), any())).thenReturn(Mono.empty());

        pass();

        assertThat(plan.current()).isEmpty();
    }

    //HAS-477: the run of a twin row of the same outage, carried to the row kept, counts as its run
    @Test
    void should_never_start_a_run_for_an_outage_that_carries_the_run_of_its_twin() {
        when(kit.outages().findFirstByClosedAtIsNullAndKindAndScopeAndSkipAutomationFalseAndStartsAtAfterOrderByStartsAtAscIdAsc(
            eq("planned"), eq("street"), any())).thenReturn(Mono.just(OUTAGE.withChargeRunId(41L)));
        kit.table().put(41L, kit.ended().withId(41L).withPlannedOutageId(9L));
        kit.moveTo(LocalDateTime.of(2026, 11, 3, 17, 0));

        pass();

        verify(kit.client(), never()).setSelfUse(any());
    }
}

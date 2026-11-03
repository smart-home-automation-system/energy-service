package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.solaxcloud.SolaxCloudException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;

import static cloud.cholewa.energy.charge.ChargeSamples.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the watch of a charge (HAS-336) with a stubbed SolaX cloud and a clock moved on: the delivery of its command, its end by
//target or window, the baseline set back at most once a minute
class ChargeWatchTest {

    private final ChargeFixture fixture = new ChargeFixture();

    private void pass() {
        fixture.watch.watch().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_tell_a_charge_the_inverter_carries_out() {
        fixture.table.put(1L, ChargeSamples.requested());
        when(fixture.client.requestStatus("r-1", "H1234567890ABC")).thenReturn(Mono.just(4));
        fixture.moveTo(NOW.plusSeconds(10));

        pass();

        assertThat(fixture.only()).satisfies(run -> {
            assertThat(run.deliveryStatus()).isEqualTo(4);
            assertThat(run.deliveredAt()).isEqualTo(NOW.plusSeconds(10));
        });
        verify(fixture.publisher).publishInfo("Battery charging from the grid started (dashboard): 45 % to 100 %, window"
            + " 14:05-19:30.");
    }

    //the first real run (2026-11-03): the cloud answered the result of the command 10403 while the battery charged at 5.1 kW
    @Test
    void should_take_a_charge_from_the_grid_as_carried_out_when_the_cloud_refuses_its_result() {
        fixture.table.put(1L, ChargeSamples.requested());
        when(fixture.client.requestStatus("r-1", "H1234567890ABC")).thenReturn(Mono.error(
            new SolaxCloudException("The SolaX cloud answered code 10403")));
        fixture.battery(7.0, 5070.0, false);
        fixture.grid(5900.0);
        fixture.moveTo(NOW.plusSeconds(20));

        pass();

        assertThat(fixture.only()).satisfies(run -> {
            assertThat(run.deliveredAt()).isEqualTo(NOW.plusSeconds(20));
            assertThat(run.deliveryStatus()).isNull();
            assertThat(run.open()).isTrue();
        });
        verify(fixture.client, never()).setSelfUse(any());
    }

    //unconfirmed after the delivery timeout: failed, the baseline set back, told without blaming the inverter
    @Test
    void should_tell_a_charge_not_confirmed_in_time() {
        fixture.table.put(1L, ChargeSamples.requested());
        when(fixture.client.requestStatus("r-1", "H1234567890ABC")).thenReturn(Mono.empty());
        when(fixture.client.setSelfUse(any())).thenReturn(Mono.just("r-2"));
        fixture.battery(7.0, -30.0, false);
        fixture.moveTo(NOW.plus(Duration.ofMinutes(2)));

        pass();

        assertThat(fixture.only().socAtEnd()).isEqualTo(7.0);
        verify(fixture.publisher).publishInfo("Battery charging from the grid ended - the command was not confirmed in time"
            + " at 7 % (started 14:05 at 45 %); the inverter set back to Self Use.");
    }

    //the DoD of HAS-336: a failed delivery (status 5 from a stub) ends the run as failed with DEVICE_FAILED
    @Test
    void should_fail_a_charge_the_inverter_did_not_carry_out() {
        fixture.table.put(1L, ChargeSamples.requested());
        when(fixture.client.requestStatus("r-1", "H1234567890ABC")).thenReturn(Mono.just(5));

        pass();

        assertThat(fixture.only()).satisfies(run -> {
            assertThat(run.endedBy()).isEqualTo("failed");
            assertThat(run.failure()).isEqualTo("DEVICE_FAILED");
            assertThat(run.deliveryStatus()).isEqualTo(5);
            assertThat(run.restorePending()).isFalse();
        });
        verify(fixture.client, never()).setSelfUse(any());
        verify(fixture.publisher).publishInfo("Battery charging from the grid not started (dashboard): the SolaX cloud"
            + " answered status 5.");
    }

    @Test
    void should_fail_a_charge_of_an_inverter_offline_as_unavailable() {
        fixture.table.put(1L, ChargeSamples.requested());
        when(fixture.client.requestStatus("r-1", "H1234567890ABC")).thenReturn(Mono.just(1));

        pass();

        assertThat(fixture.only().failure()).isEqualTo("INVERTER_UNAVAILABLE");
    }

    //status 6: the inverter may have taken it - the baseline set back
    @Test
    void should_set_the_baseline_back_after_a_timed_out_command() {
        fixture.table.put(1L, ChargeSamples.requested());
        when(fixture.client.requestStatus("r-1", "H1234567890ABC")).thenReturn(Mono.just(6));
        when(fixture.client.setSelfUse(any())).thenReturn(Mono.just("r-2"));

        pass();

        assertThat(fixture.only()).satisfies(run -> {
            assertThat(run.endedBy()).isEqualTo("failed");
            assertThat(run.failure()).isEqualTo("DEVICE_FAILED");
            assertThat(run.restoreRequestId()).isEqualTo("r-2");
        });
    }

    @Test
    void should_wait_for_the_command_then_fail_it_after_the_delivery_timeout() {
        fixture.table.put(1L, ChargeSamples.requested());
        when(fixture.client.requestStatus("r-1", "H1234567890ABC")).thenReturn(Mono.just(3));
        when(fixture.client.setSelfUse(any())).thenReturn(Mono.just("r-2"));
        fixture.moveTo(NOW.plusSeconds(110));

        pass();
        assertThat(fixture.only().open()).isTrue();
        assertThat(fixture.only().deliveryStatus()).isEqualTo(3);

        fixture.moveTo(NOW.plus(Duration.ofMinutes(2)));
        pass();
        assertThat(fixture.only().endedBy()).isEqualTo("failed");
        assertThat(fixture.only().restoreRequestId()).isEqualTo("r-2");
    }

    @Test
    void should_end_a_charge_at_its_target_and_set_the_baseline_back() {
        fixture.table.put(1L, ChargeSamples.charging());
        fixture.battery(100.0, false);
        when(fixture.client.setSelfUse(any())).thenReturn(Mono.just("r-2"));
        fixture.moveTo(LocalDateTime.of(2026, 11, 3, 17, 40));

        pass();

        assertThat(fixture.only()).satisfies(run -> {
            assertThat(run.endedBy()).isEqualTo("target");
            assertThat(run.socAtEnd()).isEqualTo(100.0);
            assertThat(run.restoreRequestId()).isEqualTo("r-2");
            assertThat(run.restoredAt()).isEqualTo(LocalDateTime.of(2026, 11, 3, 17, 40));
        });
        verify(fixture.publisher).publishInfo("Battery charging from the grid ended - target reached at 100 % (started"
            + " 14:05 at 45 %); the inverter set back to Self Use.");
    }

    @Test
    void should_end_a_charge_at_the_end_of_its_window() {
        fixture.table.put(1L, ChargeSamples.charging());
        fixture.battery(92.0, false);
        fixture.moveTo(LocalDateTime.of(2026, 11, 3, 19, 29, 50));
        pass();
        assertThat(fixture.only().open()).isTrue();

        fixture.moveTo(LocalDateTime.of(2026, 11, 3, 19, 30));
        pass();
        assertThat(fixture.only().endedBy()).isEqualTo("window");
    }

    //at most one write a minute: a baseline refused is tried again a minute later, then the watch rests
    @Test
    void should_try_the_baseline_again_a_minute_later_and_then_rest() {
        fixture.table.put(1L, ChargeSamples.charging().withEndedAt(NOW).withEndedBy("target"));
        when(fixture.client.setSelfUse(any())).thenReturn(Mono.error(new IllegalStateException("down")));

        pass();
        fixture.moveTo(NOW.plusSeconds(30));
        pass();
        verify(fixture.client, times(1)).setSelfUse(any());

        when(fixture.client.setSelfUse(any())).thenReturn(Mono.just("r-3"));
        fixture.moveTo(NOW.plusSeconds(60));
        pass();
        assertThat(fixture.only().restorePending()).isFalse();

        clearInvocations(fixture.repository);
        pass();
        pass();
        verify(fixture.repository, times(1)).findFirstByOrderByRequestedAtDesc();
    }

    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        fixture.table.put(1L, ChargeSamples.requested());
        when(fixture.client.requestStatus(anyString(), eq("H1234567890ABC"))).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(fixture.watch::watch)
            .expectSubscription()
            .expectNoEvent(ChargeWatch.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
        assertThat(Duration.ofSeconds(10)).isGreaterThan(ChargeWatch.LIMIT);
    }
}

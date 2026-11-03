package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.api.model.ChargeRequest;
import cloud.cholewa.energy.error.EnergyError;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.solaxcloud.SolaxCloudException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static cloud.cholewa.energy.charge.ChargeSamples.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//a charge started and cancelled through the API (HAS-336): the checks before the first write, the run stored before its
//command, a refused command a failed run, a cancel setting the baseline back
class ChargeServiceTest {

    private final ChargeFixture fixture = new ChargeFixture();

    private static void refusedWith(final Throwable failure, final EnergyError error) {
        assertThat(failure).isInstanceOfSatisfying(EnergyException.class, refused ->
            assertThat(refused.getError()).isEqualTo(error));
    }

    @Test
    void should_store_the_run_and_send_its_command() {
        fixture.service.start(null, "dashboard").as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.state()).isEqualTo("requested");
                assertThat(reply.targetSoc()).isEqualTo(100);
                assertThat(reply.windowStart()).isEqualTo(LocalDateTime.of(2026, 11, 3, 14, 5));
                assertThat(reply.windowEnd()).isEqualTo(LocalDateTime.of(2026, 11, 3, 18, 0));
                assertThat(reply.socAtStart()).isEqualTo(45.0);
            })
            .verifyComplete();

        assertThat(fixture.only().requestId()).isEqualTo("r-1");
        verify(fixture.client).setSelfUse(argThat((JsonNode body) -> body.path("chargeUpperSoc").asInt() == 100
            && body.path("chargeStartTimePeriod1").asString().equals("14:05")
            && body.path("chargeEndTimePeriod1").asString().equals("18:00")
            && body.path("snList").get(0).asString().equals("H1234567890ABC")));
    }

    @Test
    void should_charge_to_the_target_and_until_asked() {
        fixture.service.start(new ChargeRequest(80, LocalDateTime.of(2026, 11, 3, 16, 0)), "automation")
            .as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.targetSoc()).isEqualTo(80);
                assertThat(reply.windowEnd()).isEqualTo(LocalDateTime.of(2026, 11, 3, 16, 0));
                assertThat(reply.source()).isEqualTo("automation");
            })
            .verifyComplete();
    }

    @Test
    void should_refuse_a_second_charge_while_one_runs() {
        fixture.table.put(1L, ChargeSamples.charging());

        fixture.service.start(null, "dashboard").as(StepVerifier::create)
            .verifyErrorSatisfies(failure -> refusedWith(failure, EnergyError.CHARGE_IN_PROGRESS));
        verify(fixture.client, never()).setSelfUse(any());
    }

    @Test
    void should_refuse_a_second_charge_while_one_is_being_set_back() {
        fixture.table.put(1L, ChargeSamples.charging().withEndedAt(NOW).withEndedBy("target"));

        fixture.service.start(null, "dashboard").as(StepVerifier::create)
            .verifyErrorSatisfies(failure -> refusedWith(failure, EnergyError.CHARGE_IN_PROGRESS));
    }

    //the task: at most one write a minute
    @Test
    void should_refuse_a_write_within_a_minute_of_the_last() {
        fixture.service.start(null, "dashboard").as(StepVerifier::create).expectNextCount(1).verifyComplete();
        fixture.table.put(1L, fixture.only().withEndedAt(NOW).withEndedBy("failed").withRestoredAt(NOW));
        fixture.moveTo(NOW.plusSeconds(59));

        fixture.service.start(null, "dashboard").as(StepVerifier::create)
            .verifyErrorSatisfies(failure -> refusedWith(failure, EnergyError.WRITE_TOO_SOON));

        fixture.moveTo(NOW.plusSeconds(60));
        fixture.service.start(null, "dashboard").as(StepVerifier::create).expectNextCount(1).verifyComplete();
    }

    @Test
    void should_refuse_a_battery_at_its_target() {
        fixture.battery(100.0, false);

        fixture.service.start(null, "dashboard").as(StepVerifier::create)
            .verifyErrorSatisfies(failure -> refusedWith(failure, EnergyError.TARGET_REACHED));
        assertThat(fixture.table).isEmpty();
    }

    @Test
    void should_refuse_a_charge_while_the_soc_is_not_known() {
        fixture.battery(45.0, true);

        fixture.service.start(null, "dashboard").as(StepVerifier::create)
            .verifyErrorSatisfies(failure -> refusedWith(failure, EnergyError.INVERTER_UNAVAILABLE));
    }

    @Test
    void should_refuse_a_charge_before_the_registry_was_read() {
        when(fixture.registry.current()).thenReturn(Optional.empty());

        fixture.service.start(null, "dashboard").as(StepVerifier::create)
            .verifyErrorSatisfies(failure -> refusedWith(failure, EnergyError.REGISTRY_UNAVAILABLE));
    }

    @Test
    void should_refuse_a_target_out_of_bounds() {
        fixture.service.start(new ChargeRequest(5, null), "dashboard").as(StepVerifier::create)
            .verifyErrorSatisfies(failure -> refusedWith(failure, EnergyError.INVALID_CHARGE));
    }

    //a command the cloud refused: the run failed, nothing to set back
    @Test
    void should_fail_the_run_whose_command_the_cloud_refused() {
        when(fixture.client.setSelfUse(any())).thenReturn(Mono.error(new SolaxCloudException(
            "The SolaX cloud answered code 10500")));

        fixture.service.start(null, "dashboard").as(StepVerifier::create)
            .verifyErrorSatisfies(failure -> refusedWith(failure, EnergyError.DEVICE_FAILED));

        assertThat(fixture.only()).satisfies(run -> {
            assertThat(run.endedBy()).isEqualTo("failed");
            assertThat(run.failure()).isEqualTo("DEVICE_FAILED");
            assertThat(run.restorePending()).isFalse();
        });
    }

    @Test
    void should_cancel_the_charge_and_set_the_baseline_back() {
        fixture.table.put(1L, ChargeSamples.charging());
        fixture.battery(60.0, false);
        when(fixture.client.setSelfUse(any())).thenReturn(Mono.just("r-2"));

        fixture.service.cancel().as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.state()).isEqualTo("ended");
                assertThat(reply.endedBy()).isEqualTo("cancel");
                assertThat(reply.socAtEnd()).isEqualTo(60.0);
            })
            .verifyComplete();

        assertThat(fixture.only().restoreRequestId()).isEqualTo("r-2");
        verify(fixture.client).setSelfUse(argThat((JsonNode body) -> body.path("chargeUpperSoc").asInt() == 10
            && body.path("chargeStartTimePeriod1").asString().equals("00:00")
            && body.path("chargeEndTimePeriod1").asString().equals("00:00")));
        verify(fixture.publisher).publishInfo("Battery charging from the grid ended - cancelled at 60 % (started 14:05 at"
            + " 45 %); the inverter set back to Self Use.");
    }

    //a baseline the cloud refused stays due - the watch tries it again
    @Test
    void should_keep_the_baseline_due_when_the_cloud_refused_it() {
        fixture.table.put(1L, ChargeSamples.charging());
        when(fixture.client.setSelfUse(any())).thenReturn(Mono.error(new SolaxCloudException("down")));

        fixture.service.cancel().as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.state()).isEqualTo("ending"))
            .verifyComplete();
        verify(fixture.publisher, never()).publishInfo(any());
    }

    @Test
    void should_refuse_a_cancel_without_a_charge() {
        fixture.service.cancel().as(StepVerifier::create)
            .verifyErrorSatisfies(failure -> refusedWith(failure, EnergyError.NOT_FOUND_CHARGE));
    }

    @Test
    void should_answer_the_charge_going_on_with_the_battery_now() {
        fixture.table.put(1L, ChargeSamples.charging());
        fixture.battery(90.0, false);

        fixture.service.current().as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.active()).isTrue();
                assertThat(reply.run().state()).isEqualTo("charging");
                assertThat(reply.soc()).isEqualTo(90.0);
                assertThat(reply.power()).isEqualTo(4800.0);
                assertThat(reply.minutesLeft()).isEqualTo(32L);
            })
            .verifyComplete();
    }

    @Test
    void should_answer_no_charge_once_it_is_over() {
        fixture.table.put(1L, ChargeSamples.charging().withEndedAt(NOW).withEndedBy("target").withRestoredAt(NOW));

        fixture.service.current().as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.active()).isFalse();
                assertThat(reply.run()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void should_refuse_a_range_of_runs_longer_than_a_year() {
        fixture.service.runs(NOW, NOW.plusDays(367)).as(StepVerifier::create)
            .verifyErrorSatisfies(failure -> refusedWith(failure, EnergyError.INVALID_RANGE));
        fixture.table.put(1L, ChargeSamples.charging());
        fixture.service.runs(NOW.minusDays(1), NOW.plusDays(1)).as(StepVerifier::create)
            .assertNext(runs -> assertThat(runs).extracting(reply -> reply.id()).isEqualTo(List.of(1L)))
            .verifyComplete();
    }
}

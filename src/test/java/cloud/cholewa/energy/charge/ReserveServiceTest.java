package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.api.model.ReserveRequest;
import cloud.cholewa.energy.error.EnergyError;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.solaxcloud.SolaxCloudException;
import cloud.cholewa.home.model.InverterBrand;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

import java.util.Optional;

import static cloud.cholewa.energy.charge.ChargeSamples.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the battery reserve (HAS-494): a change stored and written at once when it may be, held for the end of a charge, written
//by the next pass after a write within the minute; a failed write told once and tried again after 10 minutes; a command
//sent followed; a reserve set until a time ended and told; nothing written while nothing is due
class ReserveServiceTest {

    private static final String SERIAL = "H1234567890ABC";

    private final ChargeFixture fixture = new ChargeFixture();

    private static boolean reserveOf(final JsonNode body, final int level) {
        return body.path("minSoc").asInt() == level && body.path("chargeUpperSoc").asInt() == level
            && body.path("chargeFromGridEnable").asInt() == 1
            && body.path("chargeStartTimePeriod1").asString().equals("00:00")
            && body.path("chargeEndTimePeriod1").asString().equals("23:59");
    }

    private static boolean baseline(final JsonNode body) {
        return body.path("minSoc").asInt() == 10 && body.path("chargeUpperSoc").asInt() == 10
            && body.path("chargeEndTimePeriod1").asString().equals("00:00");
    }

    private void pass() {
        fixture.reserve.pass().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_store_the_reserve_and_write_it_at_once() {
        fixture.reserve.replace(new ReserveRequest(true, 80, null)).as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.enabled()).isTrue();
                assertThat(reply.level()).isEqualTo(80);
                assertThat(reply.writeState()).isEqualTo("sent");
                assertThat(reply.updatedAt()).isEqualTo(NOW);
                assertThat(reply.writeAttemptedAt()).isEqualTo(NOW);
            })
            .verifyComplete();

        assertThat(fixture.reserveRow.get().writeRequestId()).isEqualTo("r-1");
        verify(fixture.client).setBackUp(argThat((JsonNode body) -> reserveOf(body, 80)
            && body.path("snList").get(0).asString().equals(SERIAL)));
        //the reserve in Back Up (owner, 2026-11-24 - the floor of Self Use was not taken)
        verify(fixture.client, never()).setSelfUse(any());
    }

    @Test
    void should_write_the_baseline_when_switched_off() {
        fixture.reserveRow.set(ChargeSamples.reserveHeld());

        fixture.reserve.replace(new ReserveRequest(false, 80, null)).as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.enabled()).isFalse())
            .verifyComplete();

        verify(fixture.client).setSelfUse(argThat(ReserveServiceTest::baseline));
    }

    @Test
    void should_refuse_a_reserve_out_of_its_rules_and_change_nothing() {
        fixture.reserve.replace(new ReserveRequest(true, 83, null)).as(StepVerifier::create)
            .verifyErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(EnergyException.class,
                refused -> assertThat(refused.getError()).isEqualTo(EnergyError.INVALID_RESERVE)));

        assertThat(fixture.reserveRow.get()).isEqualTo(ChargeSamples.reserveNeverWritten());
        verify(fixture.client, never()).setBackUp(any());
    }

    @Test
    void should_leave_the_write_to_the_end_of_a_charge_going_on() {
        fixture.table.put(1L, ChargeSamples.charging());

        fixture.reserve.replace(new ReserveRequest(true, 60, null)).as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.writeState()).isEqualTo("pending"))
            .verifyComplete();
        pass();

        verify(fixture.client, never()).setBackUp(any());
    }

    @Test
    void should_write_a_change_within_the_minute_with_the_next_pass() {
        fixture.reserve.replace(new ReserveRequest(true, 80, null)).as(StepVerifier::create)
            .expectNextCount(1).verifyComplete();
        when(fixture.client.setBackUp(any())).thenReturn(Mono.just("r-2"));

        fixture.reserve.replace(new ReserveRequest(true, 90, null)).as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.writeState()).isEqualTo("pending"))
            .verifyComplete();
        fixture.moveTo(NOW.plusSeconds(30));
        pass();
        assertThat(fixture.reserveRow.get().writeOutcome()).isEqualTo("pending");

        fixture.moveTo(NOW.plusMinutes(1));
        pass();

        assertThat(fixture.reserveRow.get().writeOutcome()).isEqualTo("sent");
        assertThat(fixture.reserveRow.get().writeRequestId()).isEqualTo("r-2");
        verify(fixture.client).setBackUp(argThat((JsonNode body) -> reserveOf(body, 90)));
    }

    @Test
    void should_tell_a_failed_write_once_and_try_again_after_ten_minutes() {
        when(fixture.client.setBackUp(any())).thenReturn(Mono.error(new SolaxCloudException("code 10500")));

        fixture.reserve.replace(new ReserveRequest(true, 80, null)).as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.writeState()).isEqualTo("failed"))
            .verifyComplete();
        fixture.moveTo(NOW.plusMinutes(9));
        pass();
        verify(fixture.client, times(1)).setBackUp(any());

        fixture.moveTo(NOW.plusMinutes(10));
        pass();
        verify(fixture.client, times(2)).setBackUp(any());
        verify(fixture.publisher, times(1)).publishAlert("The battery reserve of 80 % was not written to the "
            + "inverter: the SolaX cloud refused the command; tried again every 10 minutes.");

        when(fixture.client.setBackUp(any())).thenReturn(Mono.just("r-3"));
        fixture.moveTo(NOW.plusMinutes(20));
        pass();

        assertThat(fixture.reserveRow.get().writeOutcome()).isEqualTo("sent");
        verify(fixture.publisher).publishInfo("The battery reserve of 80 % is written to the inverter again.");
    }

    @Test
    void should_take_a_command_the_inverter_executes() {
        fixture.reserveRow.set(ChargeSamples.reserveHeld().withWriteOutcome("sent").withWriteRequestId("r-9")
            .withWriteAttemptedAt(NOW.minusSeconds(20)));
        when(fixture.client.requestStatus("r-9", SERIAL)).thenReturn(Mono.just(4));

        pass();

        assertThat(fixture.reserveRow.get().writeOutcome()).isEqualTo("taken");
        verify(fixture.client, never()).setBackUp(any());
    }

    @Test
    void should_fail_a_command_not_carried_out_and_tell_it() {
        fixture.reserveRow.set(ChargeSamples.reserveHeld().withWriteOutcome("sent").withWriteRequestId("r-9")
            .withWriteAttemptedAt(NOW.minusSeconds(20)));
        when(fixture.client.requestStatus("r-9", SERIAL)).thenReturn(Mono.just(5));

        pass();

        assertThat(fixture.reserveRow.get().writeOutcome()).isEqualTo("failed");
        verify(fixture.publisher).publishAlert("The battery reserve of 80 % was not written to the inverter: the SolaX "
            + "cloud answered status 5; tried again every 10 minutes.");
    }

    @Test
    void should_stop_asking_for_a_command_after_the_delivery_timeout() {
        fixture.reserveRow.set(ChargeSamples.reserveHeld().withWriteOutcome("sent").withWriteRequestId("r-9")
            .withWriteAttemptedAt(NOW.minusMinutes(2)));

        pass();

        assertThat(fixture.reserveRow.get().writeOutcome()).isEqualTo("sent");
        verify(fixture.client, never()).requestStatus(anyString(), anyString());
    }

    @Test
    void should_end_a_reserve_set_until_a_time_and_tell_it() {
        fixture.reserveRow.set(ChargeSamples.reserveHeld().withUntil(NOW.plusHours(2)));
        pass();
        verify(fixture.client, never()).setSelfUse(any());

        fixture.moveTo(NOW.plusHours(2));
        pass();

        assertThat(fixture.reserveRow.get().enabled()).isFalse();
        assertThat(fixture.reserveRow.get().until()).isNull();
        assertThat(fixture.reserveRow.get().writeOutcome()).isEqualTo("sent");
        verify(fixture.client).setSelfUse(argThat(ReserveServiceTest::baseline));
        verify(fixture.publisher).publishInfo("Battery reserve of 80 % ended (set until 2026-11-03 16:05); the "
            + "inverter set back to the baseline Self Use.");
    }

    @Test
    void should_write_nothing_while_nothing_is_due() {
        pass();
        fixture.reserveRow.set(ChargeSamples.reserveHeld());
        pass();

        verify(fixture.client, never()).setBackUp(any());
    }

    @Test
    void should_refuse_a_request_without_a_body() {
        fixture.reserve.replace(null).as(StepVerifier::create)
            .verifyErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(EnergyException.class,
                refused -> assertThat(refused.getError()).isEqualTo(EnergyError.INVALID_RESERVE)));
    }

    @Test
    void should_fail_a_write_without_a_solax_in_the_registry_and_tell_it() {
        when(fixture.registry.of(InverterBrand.SOLAX)).thenReturn(Optional.empty());

        fixture.reserve.replace(new ReserveRequest(true, 80, null)).as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.writeState()).isEqualTo("failed"))
            .verifyComplete();

        verify(fixture.publisher).publishAlert("The battery reserve of 80 % was not written to the inverter: no SOLAX "
            + "with a serial number in the device registry; tried again every 10 minutes.");
    }

    @Test
    void should_leave_the_write_pending_while_the_registry_is_not_read() {
        when(fixture.registry.current()).thenReturn(Optional.empty());

        fixture.reserve.replace(new ReserveRequest(true, 80, null)).as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.writeState()).isEqualTo("pending"))
            .verifyComplete();

        verify(fixture.publisher, never()).publishAlert(anyString());
    }

    //a command the cloud took is never told as not written - the failed save reaches the caller
    @Test
    void should_not_tell_a_sent_command_as_not_written_when_its_save_fails() {
        doReturn(Mono.error(new IllegalStateException("database down"))).when(fixture.reserves)
            .save(argThat(row -> row != null && "sent".equals(row.writeOutcome())));

        fixture.reserve.replace(new ReserveRequest(true, 80, null)).as(StepVerifier::create)
            .verifyError(IllegalStateException.class);

        verify(fixture.publisher, never()).publishAlert(anyString());
    }

    @Test
    void should_fail_the_read_of_a_database_without_the_row() {
        fixture.reserveRow.set(null);

        fixture.reserve.settings().as(StepVerifier::create)
            .verifyErrorSatisfies(failure -> assertThat(failure).isInstanceOf(IllegalStateException.class));
    }
}

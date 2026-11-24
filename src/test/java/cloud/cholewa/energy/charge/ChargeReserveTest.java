package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.api.model.ChargeRequest;
import cloud.cholewa.energy.api.model.ReserveRequest;
import cloud.cholewa.energy.error.EnergyError;
import cloud.cholewa.energy.error.EnergyException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//a charge while the battery reserve holds (HAS-494): a target below the reserve refused, the charge on the floor of the
//reserve, its end setting the reserve back - not the baseline - and recording the write on the reserve
class ChargeReserveTest {

    private final ChargeFixture fixture = new ChargeFixture();

    ChargeReserveTest() {
        fixture.reserveRow.set(ChargeSamples.reserveHeld());
    }

    @Test
    void should_refuse_a_target_below_the_reserve() {
        fixture.service.start(new ChargeRequest(70, null), "dashboard").as(StepVerifier::create)
            .verifyErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(EnergyException.class,
                refused -> {
                    assertThat(refused.getError()).isEqualTo(EnergyError.INVALID_CHARGE);
                    assertThat(refused.getMessage()).isEqualTo("targetSoc must not be below the battery reserve of 80 %");
                }));

        assertThat(fixture.table).isEmpty();
        verify(fixture.client, never()).setSelfUse(any());
    }

    @Test
    void should_charge_on_the_floor_of_the_reserve() {
        fixture.service.start(null, "dashboard").as(StepVerifier::create).expectNextCount(1).verifyComplete();

        verify(fixture.client).setSelfUse(argThat((JsonNode body) -> body.path("minSoc").asInt() == 80
            && body.path("chargeUpperSoc").asInt() == 100));
    }

    @Test
    void should_take_any_target_once_the_reserve_is_switched_off() {
        fixture.reserveRow.set(ChargeSamples.reserveHeld().withEnabled(false));

        fixture.service.start(new ChargeRequest(70, null), "dashboard").as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.targetSoc()).isEqualTo(70))
            .verifyComplete();
    }

    @Test
    void should_set_the_reserve_back_at_the_end_of_a_charge() {
        fixture.table.put(1L, ChargeSamples.charging());
        fixture.battery(90.0, false);
        when(fixture.client.setBackUp(any())).thenReturn(Mono.just("r-2"));

        fixture.service.cancel().as(StepVerifier::create).expectNextCount(1).verifyComplete();

        verify(fixture.client).setBackUp(argThat((JsonNode body) -> body.path("minSoc").asInt() == 80
            && body.path("chargeUpperSoc").asInt() == 80
            && body.path("chargeEndTimePeriod1").asString().equals("23:59")));
        assertThat(fixture.reserveRow.get().writeOutcome()).isEqualTo("sent");
        assertThat(fixture.reserveRow.get().writeRequestId()).isEqualTo("r-2");
        verify(fixture.publisher).publishInfo(endsWith("; the inverter set back to the battery reserve of 80 % (Back Up)."));
    }

    //a change while a charge runs waits for its end: the watch ends the run at its target and writes the new level
    @Test
    void should_write_a_reserve_changed_during_a_charge_at_its_end() {
        fixture.table.put(1L, ChargeSamples.charging());
        fixture.reserve.replace(new ReserveRequest(true, 60, null)).as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.writeState()).isEqualTo("pending"))
            .verifyComplete();
        fixture.battery(100.0, false);
        when(fixture.client.setBackUp(any())).thenReturn(Mono.just("r-3"));

        fixture.watch.pass().as(StepVerifier::create).verifyComplete();

        assertThat(fixture.only().endedBy()).isEqualTo("target");
        verify(fixture.client).setBackUp(argThat((JsonNode body) -> body.path("minSoc").asInt() == 60
            && body.path("chargeUpperSoc").asInt() == 60
            && body.path("chargeEndTimePeriod1").asString().equals("23:59")));
        assertThat(fixture.reserveRow.get().writeOutcome()).isEqualTo("sent");
        assertThat(fixture.reserveRow.get().writeRequestId()).isEqualTo("r-3");
    }
}

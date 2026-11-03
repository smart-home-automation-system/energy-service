package cloud.cholewa.energy.api.model;

//The body of PUT /home/energy/automation (HAS-338), every field required: chargeBeforeOutage - the switch; targetSoc 10 -
//100; chargePowerKw 1 - 8; marginMinutes 0 - 720
public record AutomationRequest(
    Boolean chargeBeforeOutage,
    Integer targetSoc,
    Double chargePowerKw,
    Integer marginMinutes
) {
}

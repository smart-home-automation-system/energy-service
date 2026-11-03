package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

//GET /home/energy/battery/charge (HAS-336) - the contract of the energy panel (HAS-342): active - whether a charge runs or
//is being ended (always written); run - that charge; soc (%) and power (W, positive while charging) of the battery now;
//minutesLeft - to the target at the charge power. What is not known is left out; the start the automation plans comes
//with HAS-338
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChargeReply(
    @JsonInclude(JsonInclude.Include.ALWAYS) boolean active,
    ChargeRunReply run,
    Double soc,
    Double power,
    Long minutesLeft
) {
}

package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//GET /home/energy/battery/charge (HAS-336) - the contract of the energy panel (HAS-342): active - whether a charge runs or
//is being ended (always written); run - that charge; soc (%) and power (W, positive while charging) of the battery now;
//minutesLeft - to the target at the charge power; plannedStart - when the automation would start the charge before the next
//planned outage of the street at the SOC now (HAS-338), whether its switch is on or not. What is not known is left out
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChargeReply(
    @JsonInclude(JsonInclude.Include.ALWAYS) boolean active,
    ChargeRunReply run,
    Double soc,
    Double power,
    Long minutesLeft,
    LocalDateTime plannedStart
) {
}

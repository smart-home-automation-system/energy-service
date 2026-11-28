package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//GET and PUT /home/energy/automation (HAS-338) - the contract of the energy panel (HAS-342): the switch of the automatic
//charge before a planned outage (off until the owner turns it on), the target SOC, the charge power the start is computed
//with (kW) and the margin before the outage (min); updatedAt - the last change, left out before the first one. Since
//HAS-523 loadShedding - the switch of the load shedding during an outage of the grid (on by default), written by its own
//PUT /home/energy/automation/shedding, so the PUT of the charge settings keeps its four fields
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AutomationReply(
    @JsonInclude(JsonInclude.Include.ALWAYS) boolean chargeBeforeOutage,
    Integer targetSoc,
    Double chargePowerKw,
    Integer marginMinutes,
    LocalDateTime updatedAt,
    @JsonInclude(JsonInclude.Include.ALWAYS) boolean loadShedding
) {
}

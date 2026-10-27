package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

//One phase of the grid meter in GET /home/energy/grid: V, A, W (signed: negative where the phase returns energy
//to the grid), VA, the power factor and Hz. A value the meter did not send is left out
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PhaseReply(
    Double voltage,
    Double current,
    Double activePower,
    Double apparentPower,
    Double powerFactor,
    Double frequency
) {
}

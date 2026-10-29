package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

//One string of panels of an inverter in GET /home/energy/inverters (HAS-328): V, A and the power it gives, W. A value
//the inverter did not send is left out
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PvReply(
    Double voltage,
    Double current,
    Double power
) {
}

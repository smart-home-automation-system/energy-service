package cloud.cholewa.energy.api.model;

import cloud.cholewa.energy.solax.RunMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.Map;

//An inverter of GET /home/energy/inverters and /inverters/{id} (HAS-328) - the contract of the dashboard. What is not
//known is left out, never 0: an inverter without a reading (the SOFAR until its task, the SOLAX after a start) answers
//its registry entry and stale.
//id - solax or sofar; registryId, name, room - its entry of the device registry
//runMode - normal, eps, fault, ... (RunMode); a code the service does not know is left out
//acPower - W the inverter gives on its AC side; phases a, b, c with voltage V, current A and activePower W
//pvPower - W of the panels together; pv - the strings pv1, pv2 with voltage V, current A and power W
//gridPower - W the meter of the inverter measures at the grid, positive while the house takes from it (as /grid)
//frequency Hz, powerFactor, temperature °C of the inverter
//dailyYield, totalYield - kWh produced today and since it was set up
//measuredAt - the house time of the reading; stale - no reading, older than its bound, or the last read failed
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InverterReply(
    String id,
    Long registryId,
    String name,
    String room,
    RunMode runMode,
    Double acPower,
    Map<String, PhaseReply> phases,
    Double pvPower,
    Map<String, PvReply> pv,
    Double gridPower,
    Double frequency,
    Double powerFactor,
    Double temperature,
    Double dailyYield,
    Double totalYield,
    LocalDateTime measuredAt,
    @JsonInclude(JsonInclude.Include.ALWAYS) boolean stale
) {
}

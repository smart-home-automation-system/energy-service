package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.Map;

//GET /home/energy/grid (HAS-327): the grid meter now - the contract of the dashboard. What the meter did not send
//is left out, never 0: no phases and no powers before the first readings, no energies before the first counters.
//phases - a, b and c, as the meter names them
//totalActivePower - W, positive while the house takes energy from the grid, negative while it returns some
//importEnergy, exportEnergy - kWh taken from and returned to the grid since the meter was set up
//direction - import, export or idle (within 20 W of nothing), from the total active power
//measuredAt - the house time the readings arrived at
//stale - true while there are no readings, they are older than energy.meter.stale-after, or the meter left the
//broker; always written out
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GridReply(
    Map<String, PhaseReply> phases,
    Double totalActivePower,
    Double importEnergy,
    Double exportEnergy,
    GridDirection direction,
    LocalDateTime measuredAt,
    @JsonInclude(JsonInclude.Include.ALWAYS) boolean stale
) {
}

package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//GET /home/energy/battery (HAS-328): the battery of the SOLAX now - the contract of the dashboard. What is not known is
//left out, never 0: before the first reading only stale.
//soc - the state of charge %; health - its state of health %; power W, positive while charging, negative while
//discharging; direction charging, discharging or idle (within 20 W of nothing); voltage V; current A (signed as the
//power); temperature °C; cycles - the charge cycles of its life; remainingKwh - the energy left, the SOC of the usable
//capacity of the plant; chargedToday, dischargedToday - kWh
//measuredAt - the house time of the reading; stale - no reading, older than its bound, or the last read failed
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatteryReply(
    Double soc,
    Double health,
    Double power,
    BatteryDirection direction,
    Double voltage,
    Double current,
    Double temperature,
    Integer cycles,
    Double remainingKwh,
    Double chargedToday,
    Double dischargedToday,
    LocalDateTime measuredAt,
    @JsonInclude(JsonInclude.Include.ALWAYS) boolean stale
) {
}

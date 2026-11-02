package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

//GET /home/energy/flows (HAS-331): where the energy of the house goes now and how the day stands - the contract of the
//dashboard. The sign convention of the house: every power is W; gridPower is positive while the house takes from the
//grid, negative while it returns; batteryPower is positive while the battery charges, negative while it discharges; the
//PV powers are never negative. A value derived from an input not known is left out, never 0 - an input is known when it
//has a value and is not stale.
//production = solaxPv + sofarPv; houseLoad = production + gridPower - batteryPower
//today - the day so far, kWh (DayTotals): production (the daily counters of both inverters), imported and exported (the
//counters of the grid meter now minus at midnight), charged and discharged (the SOLAX), houseLoad, selfConsumption and
//gridShare (0..1)
//inputs - grid, solax, sofar, battery: each with its measuredAt (left out without a reading) and stale
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlowsReply(
    Double production,
    Double solaxPv,
    Double sofarPv,
    Double gridPower,
    Double batteryPower,
    Double houseLoad,
    Today today,
    Map<String, Input> inputs
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Today(
        BigDecimal production,
        BigDecimal imported,
        BigDecimal exported,
        BigDecimal charged,
        BigDecimal discharged,
        BigDecimal houseLoad,
        BigDecimal selfConsumption,
        BigDecimal gridShare
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Input(LocalDateTime measuredAt, @JsonInclude(JsonInclude.Include.ALWAYS) boolean stale) {
    }
}

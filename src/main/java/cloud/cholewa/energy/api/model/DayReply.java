package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

//A closed day of GET /home/energy/days (HAS-331) - the contract of the dashboard. kWh; what was not established is left
//out, never 0.
//solaxYield, sofarYield - the production of each inverter; production - both
//imported, exported - taken from and returned to the grid (the grid meter)
//charged, discharged - the battery of the SOLAX
//houseLoad = production + imported - exported - charged + discharged
//selfConsumption - the share of the production used in the house; gridShare - the share of the house load taken from
//the grid; both 0..1
//cloudYield, cloudImported, cloudExported - the same day as the SolaX cloud counts it (the SOLAX and its own meter), the
//cross-check; left out when the cloud was not read late that day
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DayReply(
    LocalDate day,
    Double solaxYield,
    Double sofarYield,
    Double production,
    Double imported,
    Double exported,
    Double charged,
    Double discharged,
    Double houseLoad,
    Double selfConsumption,
    Double gridShare,
    Double cloudYield,
    Double cloudImported,
    Double cloudExported
) {
}

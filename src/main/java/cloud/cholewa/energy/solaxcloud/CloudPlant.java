package cloud.cholewa.energy.solaxcloud;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

//The plant as v2/plant/realtime_data answers it (HAS-329): the energy of the day and in total, kWh - the cross-check of
//the daily totals of HAS-331
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudPlant(
    String plantLocalTime,
    Double dailyYield,
    Double totalYield,
    Double dailyCharged,
    Double dailyDischarged,
    Double dailyImported,
    Double dailyExported,
    Double totalImported,
    Double totalExported
) {
}

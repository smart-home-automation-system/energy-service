package cloud.cholewa.energy.database;

import java.time.LocalDate;
import java.time.LocalDateTime;

//A row of pv_forecast_day (V14, HAS-372): one closed day of one inverter (solax / sofar). forecastKwh - the forecast of
//the day at a performance ratio of 1.0, actualKwh - what the inverter produced; calculatedAt - the house time it was
//written
public record PvForecastDayEntity(
    LocalDate day,
    String inverter,
    double forecastKwh,
    double actualKwh,
    LocalDateTime calculatedAt
) {
}

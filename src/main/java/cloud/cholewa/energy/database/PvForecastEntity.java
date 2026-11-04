package cloud.cholewa.energy.database;

import java.time.LocalDateTime;

//A row of pv_forecast (V13, HAS-371): the forecast of one hour (house time, its start) of the plane of one inverter
//(solax / sofar) - W/m2 on the plane and on the ground, cloud cover %, temperature °C, snowfall cm; what Open-Meteo did not
//give is null. fetchedAt - the house time of the fetch that wrote it
public record PvForecastEntity(
    LocalDateTime hour,
    String inverter,
    Double irradiance,
    Double horizontalIrradiance,
    Double cloudCover,
    Double temperature,
    Double snowfall,
    LocalDateTime fetchedAt
) {
}

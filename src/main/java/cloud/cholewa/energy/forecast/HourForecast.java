package cloud.cholewa.energy.forecast;

import java.time.LocalDateTime;

//The forecast of one hour of one plane (HAS-371). hour - the house time the hour starts at (10:00 is 10:00-11:00);
//irradiance - the mean W/m2 on the plane of the panels over the hour (global_tilted_irradiance), horizontalIrradiance -
//on the ground (shortwave_radiation), cloudCover %, temperature °C at 2 m, snowfall cm over the hour. What Open-Meteo did
//not give is null
public record HourForecast(
    LocalDateTime hour,
    Double irradiance,
    Double horizontalIrradiance,
    Double cloudCover,
    Double temperature,
    Double snowfall
) {
}

package cloud.cholewa.energy.database;

import java.time.LocalDateTime;

//A row of pv_forecast_calibration (V14, HAS-372): the performance ratio in use for one inverter, the days it was computed
//on (0 - the initial ratio) and when it last changed (house time)
public record PvForecastCalibrationEntity(
    String inverter,
    double ratio,
    int days,
    LocalDateTime calibratedAt
) {
}

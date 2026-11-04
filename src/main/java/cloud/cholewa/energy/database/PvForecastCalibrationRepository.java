package cloud.cholewa.energy.database;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

//pv_forecast_calibration (V14, HAS-372): one row an inverter. The upsert changes a row only when its ratio or its days
//changed, so calibrated_at is when the calibration last moved - not every pass that confirmed it
@Repository
@RequiredArgsConstructor
public class PvForecastCalibrationRepository {

    private static final String UPSERT = """
        INSERT INTO pv_forecast_calibration (inverter, ratio, days, calibrated_at)
        VALUES (:inverter, :ratio, :days, :calibratedAt)
        ON CONFLICT (inverter) DO UPDATE
            SET ratio         = EXCLUDED.ratio,
                days          = EXCLUDED.days,
                calibrated_at = EXCLUDED.calibrated_at
            WHERE pv_forecast_calibration.ratio <> EXCLUDED.ratio OR pv_forecast_calibration.days <> EXCLUDED.days
        """;
    private static final String ALL = """
        SELECT inverter, ratio, days, calibrated_at
        FROM pv_forecast_calibration
        ORDER BY inverter
        """;

    private final DatabaseClient databaseClient;

    //1 when the row was written or changed, 0 when it held the same ratio and days
    public Mono<Long> save(final PvForecastCalibrationEntity row) {
        return databaseClient.sql(UPSERT)
            .bind("inverter", row.inverter())
            .bind("ratio", row.ratio())
            .bind("days", row.days())
            .bind("calibratedAt", row.calibratedAt())
            .fetch()
            .rowsUpdated();
    }

    public Flux<PvForecastCalibrationEntity> findAll() {
        return databaseClient.sql(ALL)
            .map((row, metadata) -> new PvForecastCalibrationEntity(
                row.get("inverter", String.class),
                row.get("ratio", Double.class),
                row.get("days", Integer.class),
                row.get("calibrated_at", LocalDateTime.class)))
            .all();
    }
}

package cloud.cholewa.energy.database;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

//pv_forecast (V13, HAS-371): one row an hour and an inverter - the key is the pair, which Spring Data R2DBC does not map,
//so the statements are written here. A row is written only while its hour has not started: the upsert changes a row only
//when its hour is after the fetch, so what the forecast said before an hour is kept for the calibration (HAS-372)
@Repository
@RequiredArgsConstructor
public class PvForecastRepository {

    private static final String UPSERT = """
        INSERT INTO pv_forecast (hour, inverter, irradiance, horizontal_irradiance, cloud_cover, temperature, snowfall,
                                 fetched_at)
        VALUES (:hour, :inverter, :irradiance, :horizontal, :cloud, :temperature, :snowfall, :fetchedAt)
        ON CONFLICT (hour, inverter) DO UPDATE
            SET irradiance            = EXCLUDED.irradiance,
                horizontal_irradiance = EXCLUDED.horizontal_irradiance,
                cloud_cover           = EXCLUDED.cloud_cover,
                temperature           = EXCLUDED.temperature,
                snowfall              = EXCLUDED.snowfall,
                fetched_at            = EXCLUDED.fetched_at
            WHERE pv_forecast.hour > EXCLUDED.fetched_at
        """;
    private static final String BETWEEN = """
        SELECT hour, inverter, irradiance, horizontal_irradiance, cloud_cover, temperature, snowfall, fetched_at
        FROM pv_forecast
        WHERE hour >= :from AND hour < :to
        ORDER BY hour, inverter
        """;

    private final DatabaseClient databaseClient;

    //1 when the row was written, 0 when its hour had started
    public Mono<Long> save(final PvForecastEntity row) {
        return databaseClient.sql(UPSERT)
            .bind("hour", row.hour())
            .bind("inverter", row.inverter())
            .bind("irradiance", Parameter.fromOrEmpty(row.irradiance(), Double.class))
            .bind("horizontal", Parameter.fromOrEmpty(row.horizontalIrradiance(), Double.class))
            .bind("cloud", Parameter.fromOrEmpty(row.cloudCover(), Double.class))
            .bind("temperature", Parameter.fromOrEmpty(row.temperature(), Double.class))
            .bind("snowfall", Parameter.fromOrEmpty(row.snowfall(), Double.class))
            .bind("fetchedAt", row.fetchedAt())
            .fetch()
            .rowsUpdated();
    }

    //the hours of [from, to) of both inverters, by the hour and then the inverter (sofar before solax)
    public Flux<PvForecastEntity> findBetween(final LocalDateTime from, final LocalDateTime to) {
        return databaseClient.sql(BETWEEN)
            .bind("from", from)
            .bind("to", to)
            .map((row, metadata) -> new PvForecastEntity(
                row.get("hour", LocalDateTime.class),
                row.get("inverter", String.class),
                row.get("irradiance", Double.class),
                row.get("horizontal_irradiance", Double.class),
                row.get("cloud_cover", Double.class),
                row.get("temperature", Double.class),
                row.get("snowfall", Double.class),
                row.get("fetched_at", LocalDateTime.class)))
            .all();
    }
}

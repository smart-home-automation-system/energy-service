package cloud.cholewa.energy.database;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;

//pv_forecast_day (V14, HAS-372): the key is the day and the inverter, which Spring Data R2DBC does not map, so the
//statements are written here. A day is written once: a second write of the same day and inverter changes nothing
@Repository
@RequiredArgsConstructor
public class PvForecastDayRepository {

    private static final String INSERT = """
        INSERT INTO pv_forecast_day (day, inverter, forecast_kwh, actual_kwh, calculated_at)
        VALUES (:day, :inverter, :forecast, :actual, :calculatedAt)
        ON CONFLICT (day, inverter) DO NOTHING
        """;
    private static final String BETWEEN = """
        SELECT day, inverter, forecast_kwh, actual_kwh, calculated_at
        FROM pv_forecast_day
        WHERE day >= :from AND day <= :to
        ORDER BY day, inverter
        """;

    private final DatabaseClient databaseClient;

    //1 when the row was written, 0 when the day of the inverter was there already
    public Mono<Long> save(final PvForecastDayEntity row) {
        return databaseClient.sql(INSERT)
            .bind("day", row.day())
            .bind("inverter", row.inverter())
            .bind("forecast", row.forecastKwh())
            .bind("actual", row.actualKwh())
            .bind("calculatedAt", row.calculatedAt())
            .fetch()
            .rowsUpdated();
    }

    //the days of [from, to], both included, oldest first, then by the inverter (sofar before solax)
    public Flux<PvForecastDayEntity> findBetween(final LocalDate from, final LocalDate to) {
        return databaseClient.sql(BETWEEN)
            .bind("from", from)
            .bind("to", to)
            .map((row, metadata) -> new PvForecastDayEntity(
                row.get("day", LocalDate.class),
                row.get("inverter", String.class),
                row.get("forecast_kwh", Double.class),
                row.get("actual_kwh", Double.class),
                row.get("calculated_at", LocalDateTime.class)))
            .all();
    }
}

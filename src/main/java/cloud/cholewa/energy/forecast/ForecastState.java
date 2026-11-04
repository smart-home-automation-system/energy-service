package cloud.cholewa.energy.forecast;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

//What the last fetches of the forecast did (HAS-371), per plane - the inverter it feeds, in memory: a start knows nothing
//until the first fetch. fetchedAt - the house time of the last successful fetch, null before one; lastFailed - whether the
//last fetch failed. Read by GET /forecast (HAS-372) for staleness; nothing is published: a forecast is not a device
@Component
public class ForecastState {

    public record Plane(LocalDateTime fetchedAt, boolean lastFailed) {
    }

    private final Map<String, Plane> planes = new ConcurrentHashMap<>();

    void fetched(final String inverter, final LocalDateTime at) {
        planes.put(inverter, new Plane(at, false));
    }

    void failed(final String inverter) {
        planes.compute(inverter, (key, before) -> new Plane(before == null ? null : before.fetchedAt(), true));
    }

    public Optional<Plane> of(final String inverter) {
        return Optional.ofNullable(planes.get(inverter));
    }
}

package cloud.cholewa.energy.history;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;

//The nightly retention for a test of another package (the forecast of HAS-371): its pass is package-private
public final class EnergyRetentionAccess {

    private EnergyRetentionAccess() {
    }

    public static Mono<Void> purge(final DatabaseClient databaseClient, final Clock clock) {
        return new EnergyRetention(databaseClient, new RetentionProperties(Duration.ofDays(730)), clock).purge();
    }
}

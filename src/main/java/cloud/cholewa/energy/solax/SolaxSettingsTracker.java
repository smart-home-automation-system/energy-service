package cloud.cholewa.energy.solax;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//The settings of the SOLAX as last read from its dongle, in memory (HAS-518): written by SolaxSettingsReader, read by
//the battery reserve, the status and the watch of the floor. Settings read longer ago than stale-after are not known -
//never served as the settings now. A change of the work mode or of the Back Up percentages is an INFO, the mode with its
//raw code. Holds too when the service last wrote to the inverter (InverterCommands), so that the reader reads again
//after it
@Slf4j
@Component
@RequiredArgsConstructor
public class SolaxSettingsTracker {

    private final SolaxSettingsProperties properties;
    private final Clock clock;
    private final AtomicReference<Read> last = new AtomicReference<>();
    private final AtomicReference<Instant> wroteAt = new AtomicReference<>();

    //the settings and the time of the house they were read at
    public record Read(SolaxSettings settings, LocalDateTime readAt) {
    }

    //the settings while they are fresh
    public Optional<Read> current() {
        final LocalDateTime now = LocalDateTime.now(clock);
        return Optional.ofNullable(last.get())
            .filter(read -> now.isBefore(read.readAt().plus(properties.staleAfter())));
    }

    void read(final SolaxSettings settings) {
        final Read before = last.getAndSet(new Read(settings, LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)));
        if (before == null || !before.settings().equals(settings)) {
            log.info("The settings of the SOLAX: work mode {} (code {}), Back Up Min SoC {} %, Charge Battery To {} %",
                settings.workMode().wireName(), settings.workModeCode(), settings.backUpMinSoc(),
                settings.backUpChargeTo());
        }
    }

    //a write of the service to the inverter went out now
    public void wrote() {
        wroteAt.set(clock.instant());
    }

    public Optional<Instant> wroteAt() {
        return Optional.ofNullable(wroteAt.get());
    }
}

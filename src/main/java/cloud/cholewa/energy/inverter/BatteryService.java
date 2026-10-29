package cloud.cholewa.energy.inverter;

import cloud.cholewa.energy.api.model.BatteryDirection;
import cloud.cholewa.energy.api.model.BatteryReply;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.solax.SolaxReading;
import cloud.cholewa.energy.solax.SolaxState;
import cloud.cholewa.energy.solax.SolaxTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

//The battery of the SOLAX as GET /home/energy/battery answers it (HAS-328), from the state in memory - the dongle is
//never asked for a request. The energy left is the SOC of the usable capacity of the plant (energy.solax
//.battery-capacity), to two decimals. "Now" is read inside the chain
@Service
@RequiredArgsConstructor
public class BatteryService {

    private static final int KWH_DIGITS = 2;

    private final SolaxTracker solaxTracker;
    private final SolaxProperties solaxProperties;
    private final Clock clock;

    public Mono<BatteryReply> battery() {
        return Mono.fromSupplier(() -> replyOf(solaxTracker.current(), LocalDateTime.now(clock)));
    }

    BatteryReply replyOf(final SolaxState state, final LocalDateTime now) {
        final boolean stale = state.isStale(now, solaxProperties.staleAfter());
        return Optional.ofNullable(state.reading())
            .map(SolaxReading::battery)
            .map(battery -> new BatteryReply(battery.soc(), battery.soh(), battery.power(),
                Optional.ofNullable(battery.power()).map(BatteryDirection::of).orElse(null),
                battery.voltage(), battery.current(), battery.temperature(), battery.cycles(),
                Optional.ofNullable(battery.soc()).map(this::remaining).orElse(null),
                battery.chargedToday(), battery.dischargedToday(), state.readingAt(), stale))
            .orElseGet(() -> new BatteryReply(null, null, null, null, null, null, null, null, null, null, null, null,
                stale));
    }

    private double remaining(final double soc) {
        return BigDecimal.valueOf(soc)
            .multiply(BigDecimal.valueOf(solaxProperties.batteryCapacity()))
            .movePointLeft(2)
            .setScale(KWH_DIGITS, RoundingMode.HALF_UP)
            .doubleValue();
    }
}

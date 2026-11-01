package cloud.cholewa.energy.inverter;

import cloud.cholewa.energy.api.model.BatteryDirection;
import cloud.cholewa.energy.api.model.BatteryReply;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.solax.SolaxReading;
import cloud.cholewa.energy.solax.SolaxState;
import cloud.cholewa.energy.registry.Inverter;
import cloud.cholewa.energy.registry.InverterRegistry;
import cloud.cholewa.energy.solax.SolaxTracker;
import cloud.cholewa.energy.solaxcloud.CloudBattery;
import cloud.cholewa.energy.solaxcloud.SolaxCloudProperties;
import cloud.cholewa.energy.solaxcloud.SolaxCloudState;
import cloud.cholewa.energy.solaxcloud.SolaxCloudTracker;
import cloud.cholewa.home.model.InverterBrand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

//The battery of the SOLAX as GET /home/energy/battery answers it (HAS-328), from the state in memory - the dongle is
//never asked for a request. The energy left is the SOC of the usable capacity of the plant (energy.solax
//.battery-capacity), to two decimals. "Now" is read inside the chain. While the dongle is stale the battery is what the
//SolaX cloud read last, when it is of the battery of the SOLAX of the registry and fresh (HAS-329), with source cloud
@Service
@RequiredArgsConstructor
public class BatteryService {

    static final String LOCAL = "local";
    static final String CLOUD = "cloud";
    private static final int KWH_DIGITS = 2;

    private final SolaxTracker solaxTracker;
    private final SolaxProperties solaxProperties;
    private final SolaxCloudTracker solaxCloudTracker;
    private final SolaxCloudProperties solaxCloudProperties;
    private final InverterRegistry inverterRegistry;
    private final Clock clock;

    public Mono<BatteryReply> battery() {
        return Mono.fromSupplier(() -> replyOf(solaxTracker.current(), LocalDateTime.now(clock)));
    }

    BatteryReply replyOf(final SolaxState state, final LocalDateTime now) {
        final boolean stale = state.isStale(now, solaxProperties.staleAfter());
        return Optional.of(stale).filter(Boolean::booleanValue)
            .flatMap(dongleStale -> cloud(now, state.readingAt()))
            .orElseGet(() -> local(state, stale));
    }

    private BatteryReply local(final SolaxState state, final boolean stale) {
        return Optional.ofNullable(state.reading())
            .map(SolaxReading::battery)
            .map(battery -> new BatteryReply(battery.soc(), battery.soh(), battery.power(),
                Optional.ofNullable(battery.power()).map(BatteryDirection::of).orElse(null),
                battery.voltage(), battery.current(), battery.temperature(), battery.cycles(),
                Optional.ofNullable(battery.soc()).map(this::remaining).orElse(null),
                battery.chargedToday(), battery.dischargedToday(), state.readingAt(), stale, LOCAL))
            .orElseGet(() -> new BatteryReply(null, null, null, null, null, null, null, null, null, null, null, null,
                stale, null));
    }

    //the SolaX cloud while the dongle is stale: its last answer, when it is of the battery of the SOLAX, fresh and newer
    //than the reading of the dongle
    private Optional<BatteryReply> cloud(final LocalDateTime now, final LocalDateTime dongleReadingAt) {
        final SolaxCloudState cloud = solaxCloudTracker.current();
        final Optional<String> serial = inverterRegistry.of(InverterBrand.SOLAX).map(Inverter::batterySerialNumber);
        return Optional.ofNullable(cloud.battery())
            .filter(answer -> serial.isPresent() && Objects.equals(answer.deviceSn(), serial.get()))
            .filter(answer -> cloud.fresh(answer.plantLocalTime(), now, solaxCloudProperties.staleAfter(),
                dongleReadingAt))
            .map(this::cloud);
    }

    private BatteryReply cloud(final CloudBattery answer) {
        return new BatteryReply(answer.batterySOC(), answer.batterySOH(), answer.chargeDischargePower(),
            Optional.ofNullable(answer.chargeDischargePower()).map(BatteryDirection::of).orElse(null),
            answer.batteryVoltage(), answer.batteryCurrent(), answer.batteryTemperature(), answer.batteryCycleTimes(),
            Optional.ofNullable(answer.batterySOC()).map(this::remaining).orElse(null), null, null,
            SolaxCloudState.plantTime(answer.plantLocalTime()), false, CLOUD);
    }

    private double remaining(final double soc) {
        return BigDecimal.valueOf(soc)
            .multiply(BigDecimal.valueOf(solaxProperties.batteryCapacity()))
            .movePointLeft(2)
            .setScale(KWH_DIGITS, RoundingMode.HALF_UP)
            .doubleValue();
    }
}

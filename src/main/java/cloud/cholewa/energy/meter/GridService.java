package cloud.cholewa.energy.meter;

import cloud.cholewa.energy.api.model.GridDirection;
import cloud.cholewa.energy.api.model.GridReply;
import cloud.cholewa.energy.api.model.PhaseReply;
import cloud.cholewa.shelly.model.EmDataStatus;
import cloud.cholewa.shelly.model.EmStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

//The grid meter as GET /home/energy/grid answers it (HAS-327), from the state in memory - the meter is never
//asked for a request. "Now" is read inside the chain
@Service
@RequiredArgsConstructor
public class GridService {

    private final GridMeterTracker gridMeterTracker;
    private final MeterProperties meterProperties;
    private final Clock clock;

    public Mono<GridReply> grid() {
        return Mono.fromSupplier(() -> replyOf(gridMeterTracker.current(), LocalDateTime.now(clock)));
    }

    GridReply replyOf(final GridMeterState state, final LocalDateTime now) {
        final Optional<EmStatus> readings = Optional.ofNullable(state.readings());
        final Optional<EmDataStatus> counters = Optional.ofNullable(state.counters());
        final Double total = readings.map(EmStatus::getTotalActPower).orElse(null);
        return new GridReply(
            readings.map(GridService::phases).orElse(null),
            total,
            counters.map(EmDataStatus::getTotalAct).map(GridSnapshot::kwh).orElse(null),
            counters.map(EmDataStatus::getTotalActRet).map(GridSnapshot::kwh).orElse(null),
            Optional.ofNullable(total).map(GridDirection::of).orElse(null),
            state.readingsAt(),
            isStale(state, now));
    }

    private boolean isStale(final GridMeterState state, final LocalDateTime now) {
        return state.readingsAt() == null
            || Boolean.FALSE.equals(state.online())
            || state.readingsAt().isBefore(now.minus(meterProperties.staleAfter()));
    }

    private static Map<String, PhaseReply> phases(final EmStatus readings) {
        final Map<String, PhaseReply> phases = new LinkedHashMap<>();
        phases.put("a", new PhaseReply(readings.getaVoltage(), readings.getaCurrent(), readings.getaActPower(),
            readings.getaAprtPower(), readings.getaPf(), readings.getaFreq()));
        phases.put("b", new PhaseReply(readings.getbVoltage(), readings.getbCurrent(), readings.getbActPower(),
            readings.getbAprtPower(), readings.getbPf(), readings.getbFreq()));
        phases.put("c", new PhaseReply(readings.getcVoltage(), readings.getcCurrent(), readings.getcActPower(),
            readings.getcAprtPower(), readings.getcPf(), readings.getcFreq()));
        return phases;
    }
}

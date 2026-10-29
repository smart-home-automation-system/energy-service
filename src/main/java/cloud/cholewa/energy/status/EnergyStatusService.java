package cloud.cholewa.energy.status;

import cloud.cholewa.energy.api.model.SourceStatusReply;
import cloud.cholewa.energy.meter.GridMeterState;
import cloud.cholewa.energy.meter.GridMeterTracker;
import cloud.cholewa.energy.solax.SolaxState;
import cloud.cholewa.energy.solax.SolaxTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;

//What the service knows of each source of energy. The grid meter since HAS-327: reachable as the broker last said
//(its last will is false, a reading true) and the time of its newest reading. The SOLAX since HAS-328: whether the last
//read of its dongle succeeded and the time of its last reading. The other sources are not known until their tasks
//(HAS-329, HAS-330). The availability monitor of HAS-332 will judge them
@Service
@RequiredArgsConstructor
public class EnergyStatusService {

    private final GridMeterTracker gridMeterTracker;
    private final SolaxTracker solaxTracker;

    public Mono<List<SourceStatusReply>> status() {
        return Mono.fromSupplier(() -> Arrays.stream(EnergySource.values())
            .map(this::statusOf)
            .toList());
    }

    private SourceStatusReply statusOf(final EnergySource source) {
        return switch (source) {
            case METER -> meter(gridMeterTracker.current());
            case SOLAX -> solax(solaxTracker.current());
            case SOFAR, SOLAX_CLOUD -> SourceStatusReply.notKnown(source.wireName());
        };
    }

    private static SourceStatusReply meter(final GridMeterState state) {
        return new SourceStatusReply(EnergySource.METER.wireName(), state.online(), state.lastReadingAt());
    }

    private static SourceStatusReply solax(final SolaxState state) {
        return new SourceStatusReply(EnergySource.SOLAX.wireName(), state.reachable(), state.readingAt());
    }
}

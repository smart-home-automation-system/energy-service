package cloud.cholewa.energy.status;

import cloud.cholewa.energy.api.model.SourceStatusReply;
import cloud.cholewa.energy.api.model.StatusReply;
import cloud.cholewa.energy.meter.GridMeterState;
import cloud.cholewa.energy.meter.GridMeterTracker;
import cloud.cholewa.energy.outage.GridOutageService;
import cloud.cholewa.energy.sofar.SofarState;
import cloud.cholewa.energy.sofar.SofarTracker;
import cloud.cholewa.energy.solax.SolaxState;
import cloud.cholewa.energy.solax.SolaxTracker;
import cloud.cholewa.energy.solaxcloud.SolaxCloudState;
import cloud.cholewa.energy.solaxcloud.SolaxCloudTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;

//What the service knows of each source of energy. The grid meter since HAS-327: reachable as the broker last said
//(its last will is false, a reading true) and the time of its newest reading. The SOLAX since HAS-328: whether the last
//read of its dongle succeeded and the time of its last reading. The SolaX cloud since HAS-329. The SOFAR since HAS-330:
//whether the last read of the Solarman cloud brought a fresh reading and the time its logger collected it. Since HAS-334
//with the grid as the SOLAX sees it
@Service
@RequiredArgsConstructor
public class EnergyStatusService {

    private final GridMeterTracker gridMeterTracker;
    private final SolaxTracker solaxTracker;
    private final SolaxCloudTracker solaxCloudTracker;
    private final SofarTracker sofarTracker;
    private final GridOutageService gridOutageService;

    //the answer of GET /status: the sources and the grid
    public Mono<StatusReply> reply() {
        return status().map(sources -> new StatusReply(sources, gridOutageService.grid()));
    }

    public Mono<List<SourceStatusReply>> status() {
        return Mono.fromSupplier(() -> Arrays.stream(EnergySource.values())
            .map(this::statusOf)
            .toList());
    }

    private SourceStatusReply statusOf(final EnergySource source) {
        return switch (source) {
            case METER -> meter(gridMeterTracker.current());
            case SOLAX -> solax(solaxTracker.current());
            case SOLAX_CLOUD -> solaxCloud(solaxCloudTracker.current());
            case SOFAR -> sofar(sofarTracker.current());
        };
    }

    private static SourceStatusReply sofar(final SofarState state) {
        return new SourceStatusReply(EnergySource.SOFAR.wireName(), state.reachable(), state.readingAt());
    }

    private static SourceStatusReply meter(final GridMeterState state) {
        return new SourceStatusReply(EnergySource.METER.wireName(), state.online(), state.lastReadingAt());
    }

    //the SolaX cloud since HAS-329: whether its last read succeeded and when it was read
    private static SourceStatusReply solaxCloud(final SolaxCloudState state) {
        return new SourceStatusReply(EnergySource.SOLAX_CLOUD.wireName(), state.reachable(), state.readAt());
    }

    private static SourceStatusReply solax(final SolaxState state) {
        return new SourceStatusReply(EnergySource.SOLAX.wireName(), state.reachable(), state.readingAt());
    }
}

package cloud.cholewa.energy.status;

import cloud.cholewa.energy.api.model.SourceStatusReply;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;

//What the service knows of each source of energy (HAS-325). Nothing is read yet, so every source is not
//known; the reading tasks (HAS-327 onwards) fill reachable, and the availability monitor of HAS-332 judges it
@Service
public class EnergyStatusService {

    public Mono<List<SourceStatusReply>> status() {
        return Mono.fromSupplier(() -> Arrays.stream(EnergySource.values())
            .map(source -> new SourceStatusReply(source.wireName(), null))
            .toList());
    }
}

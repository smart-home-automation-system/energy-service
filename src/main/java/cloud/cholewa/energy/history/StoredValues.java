package cloud.cholewa.energy.history;

import cloud.cholewa.energy.api.model.HistoryReply;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.registry.InverterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.function.Function;

//The stored values read for the history and the days (HAS-331): a value of an inverter is read by its registry id. An
//inverter the registry does not have reads nothing (empty); before the registry was read once a value of an inverter is
//REGISTRY_UNAVAILABLE - the other values never need it
@Component
@RequiredArgsConstructor
public class StoredValues {

    private final HistoryRepository repository;
    private final InverterRegistry inverterRegistry;

    //the registry id of the inverter of a value, null for a value of no inverter
    private record Target(Long inverterId) {
    }

    Flux<HistoryReply.Point> history(final StoredValue value, final LocalDateTime from, final LocalDateTime to,
                                     final long bucketSeconds) {
        return targetOf(value)
            .flatMapMany(target -> repository.findHistory(value, target.inverterId(), from, to, bucketSeconds));
    }

    //the last value before "at"
    public Mono<BigDecimal> lastBefore(final StoredValue value, final LocalDateTime at) {
        return read(value, target -> repository.findLastBefore(value, target.inverterId(), at));
    }

    //the last value in [from, to)
    public Mono<BigDecimal> lastIn(final StoredValue value, final LocalDateTime from, final LocalDateTime to) {
        return read(value, target -> repository.findLastIn(value, target.inverterId(), from, to));
    }

    //the highest value in [from, to)
    public Mono<BigDecimal> maxIn(final StoredValue value, final LocalDateTime from, final LocalDateTime to) {
        return read(value, target -> repository.findMaxIn(value, target.inverterId(), from, to));
    }

    //the time of the first stored row with the value
    public Mono<LocalDateTime> firstTime(final StoredValue value) {
        return read(value, target -> repository.findFirstTime(value, target.inverterId()));
    }

    //whether the value can be read: false for an inverter the registry does not have
    Mono<Boolean> readable(final StoredValue value) {
        return targetOf(value).hasElement();
    }

    private <T> Mono<T> read(final StoredValue value, final Function<Target, Mono<T>> query) {
        return targetOf(value).flatMap(query);
    }

    private Mono<Target> targetOf(final StoredValue value) {
        return Mono.just(value)
            .filter(stored -> stored.inverter() == null)
            .map(stored -> new Target(null))
            .switchIfEmpty(Mono.defer(() -> Mono.justOrEmpty(inverterRegistry.current())
                .switchIfEmpty(Mono.error(EnergyException::registryUnavailable))
                .flatMap(read -> Mono.justOrEmpty(inverterRegistry.of(value.inverter())))
                .map(inverter -> new Target(inverter.id()))));
    }
}

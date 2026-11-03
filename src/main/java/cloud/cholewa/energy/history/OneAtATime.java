package cloud.cholewa.energy.history;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;

//The queries of the history one after the other (HAS-331; the charge of the battery takes one too, HAS-336) - a copy of OneAtATime of weather-service: the pool has two
//connections, and two ranges at once would leave none for the snapshots of the minute and the watches. Each query waits
//for the end of the one before it - completed, failed or cancelled - without blocking a thread. A query cancelled while it
//waits hands its turn on only once the one before it has ended too - never two queries at once
public final class OneAtATime {

    private final AtomicReference<Mono<Void>> last = new AtomicReference<>(Mono.empty());

    public <T> Mono<T> run(final Mono<T> work) {
        return Mono.defer(() -> {
            final Sinks.Empty<Void> done = Sinks.empty();
            final Mono<Void> before = last.getAndSet(done.asMono());
            return before
                .then(work)
                .doFinally(signal -> before.subscribe(null, null, done::tryEmitEmpty));
        });
    }
}

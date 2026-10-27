package cloud.cholewa.energy.meter;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

//Reads the grid meter over its RPC at the start and every five minutes (HAS-327): EM.GetStatus and
//EMData.GetStatus, each with its own error handling, so a failed one leaves the other. After a start the state
//is not known until a message or this read, and after a gap of the broker its values replace the old MQTT state.
//The calls have the timeouts of shelly-client (shelly.client.*, 3 s to connect, 5 s of silence); the whole pass is
//bounded below the five minutes between passes. A failed read changes nothing: the meter is on the broker or not,
//MQTT says which
@Slf4j
@Component
@RequiredArgsConstructor
public class MeterReconciliation {

    static final Duration LIMIT = Duration.ofMinutes(1);
    private static final int CHANNEL = 0;

    private final MeterProperties meterProperties;
    private final ShellyRpcClient shellyRpcClient;
    private final GridMeterTracker gridMeterTracker;

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT5M")
    Mono<Void> reconcile() {
        return ScheduledPass.bounded("grid meter reconciliation", LIMIT, () -> readings().then(Mono.defer(this::counters)));
    }

    private Mono<Void> readings() {
        return shellyRpcClient.emStatus(meterProperties.address(), CHANNEL)
            .doOnNext(gridMeterTracker::readings)
            .then()
            .onErrorResume(failure -> failed("readings", failure));
    }

    private Mono<Void> counters() {
        return shellyRpcClient.emDataStatus(meterProperties.address(), CHANNEL)
            .doOnNext(gridMeterTracker::counters)
            .then()
            .onErrorResume(failure -> failed("energy counters", failure));
    }

    private static Mono<Void> failed(final String what, final Throwable failure) {
        return Mono.fromRunnable(() -> log.warn("The {} of the grid meter could not be read: {}", what, failure.toString()));
    }
}

package cloud.cholewa.energy.meter;

import cloud.cholewa.shelly.codec.ShellyDecodeException;
import cloud.cholewa.shelly.codec.ShellyEvent;
import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import cloud.cholewa.shelly.codec.ShellyTopic;
import cloud.cholewa.shelly.model.EmDataStatus;
import cloud.cholewa.shelly.model.EmStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Set;

//Picks the messages of the grid meter out of every device event of the queue (about 130 a minute, HAS-327): by
//the routing key alone, before a body is read - another device, or a component of the meter that is not its
//readings (temperature:0, the BTHome sensors of the garage gates, modbus) is left without a word. Only the
//readings (em:0), the counters (emdata:0) and the online topic are decoded. A body that is not what its topic
//carries is a WARN, never an error out of the listener
@Slf4j
@Component
@RequiredArgsConstructor
public class MeterEventRouter {

    static final String READINGS = "em";
    static final String COUNTERS = "emdata";
    private static final Set<String> COMPONENTS = Set.of(READINGS, COUNTERS);

    private final MeterProperties meterProperties;
    private final ShellyMqttDecoder shellyMqttDecoder;
    private final GridMeterTracker gridMeterTracker;

    public Mono<Void> route(final String routingKey, final byte[] body) {
        return Mono.justOrEmpty(routingKey)
            .flatMap(key -> Mono.justOrEmpty(ShellyTopic.parse(key)))
            .filter(topic -> meterProperties.deviceId().equalsIgnoreCase(topic.deviceId()))
            .filter(MeterEventRouter::isRead)
            .flatMap(topic -> decode(topic, body))
            .doOnNext(this::observe)
            .onErrorResume(ShellyDecodeException.class, failure -> Mono.fromRunnable(() ->
                log.warn("A message of the grid meter could not be read: {}", failure.getMessage())))
            .then();
    }

    private static boolean isRead(final ShellyTopic topic) {
        return topic.kind() == ShellyTopic.Kind.ONLINE
            || topic.kind() == ShellyTopic.Kind.STATUS && COMPONENTS.contains(topic.component())
            && Integer.valueOf(0).equals(topic.index());
    }

    private Mono<ShellyEvent> decode(final ShellyTopic topic, final byte[] body) {
        return Mono.fromCallable(() -> shellyMqttDecoder.decode(topic, body))
            .flatMap(Mono::justOrEmpty);
    }

    private void observe(final ShellyEvent event) {
        switch (event) {
            case ShellyEvent.Online online -> gridMeterTracker.online(online.online());
            case ShellyEvent.Status status -> {
                status.as(EmStatus.class).ifPresent(gridMeterTracker::readings);
                status.as(EmDataStatus.class).ifPresent(gridMeterTracker::counters);
            }
            case ShellyEvent.Notification ignored -> {
                //the meter has the RPC over MQTT off; nothing of it is read here
            }
        }
    }
}

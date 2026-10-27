package cloud.cholewa.energy.rabbit;

import cloud.cholewa.energy.meter.MeterEventRouter;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceEventConsumerTest {

    private static final String KEY = "shellypro3em-a1b2c3d4e5f6.status.em:0";

    private final MeterEventRouter router = mock(MeterEventRouter.class);
    private final DeviceEventConsumer consumer = new DeviceEventConsumer(router);

    @Test
    void should_hand_the_event_to_the_meter_by_its_routing_key() {
        when(router.route(eq(KEY), any())).thenReturn(Mono.empty());

        consumer.consume(message()).as(StepVerifier::create).verifyComplete();

        verify(router).route(eq(KEY), any());
    }

    //an error out of the listener is handed back by the broker at once - a tight loop; the event is logged and
    //acknowledged
    @Test
    void should_complete_an_event_the_router_failed_on() {
        when(router.route(eq(KEY), any())).thenReturn(Mono.error(new IllegalStateException("unexpected")));

        consumer.consume(message()).as(StepVerifier::create).verifyComplete();
    }

    private static Message message() {
        final MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(KEY);
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }
}

package cloud.cholewa.energy;

import cloud.cholewa.commons.database.DatabaseProperties;
import cloud.cholewa.energy.meter.MeterProperties;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class EnergyServiceApplicationTest {

    @Autowired
    private DatabaseProperties databaseProperties;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private MeterProperties meterProperties;
    @Autowired
    private SolaxProperties solaxProperties;

    @Test
    void contextLoads() {
    }

    //the pool is this service's share of the 47 connections of the managed database, counted twice during a
    //rollout; a lost or mistyped key falls back to the library default without a sound
    @Test
    void should_keep_the_connection_pool_at_its_share_of_the_database_budget() {
        assertThat(databaseProperties.pool().maxSize()).isEqualTo(2);
    }

    //a context of a test must not read the meter or the inverters of the house
    @Test
    void should_schedule_nothing_under_the_test_profile() {
        assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    //the scheduler of cholewa-commons >= 1.11.0 (HAS-300): the reading tasks run on it
    @Test
    void should_give_the_scheduled_tasks_a_pool() {
        assertThat(applicationContext.getEnvironment().getProperty("spring.task.scheduling.pool.size", Integer.class))
            .isEqualTo(10);
    }

    //what has no default in a document a pod runs with - the passwords of the database and the broker, the id and
    //the address of the grid meter (private, from the manifest): the start is refused without any of them
    //(cholewa.required-properties)
    @Test
    void should_require_what_has_no_default() {
        assertThat(required(0)).containsExactly("database.password", "database-password");
        assertThat(required(1)).containsExactly("spring.rabbitmq.password", "rabbitmq-password");
        assertThat(required(2)).containsExactly("energy.meter.device-id", "meter-device-id");
        assertThat(required(3)).containsExactly("energy.meter.address", "meter-address");
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.required-properties[4].property")).isNull();
    }

    //the listener returns a Mono: with the default ack mode the container acks before the message is handled
    @Test
    void should_acknowledge_the_device_events_by_hand_three_at_a_time() {
        assertThat(applicationContext.getEnvironment().getProperty("spring.rabbitmq.listener.simple.acknowledge-mode"))
            .isEqualTo("manual");
        assertThat(applicationContext.getEnvironment().getProperty("spring.rabbitmq.listener.simple.prefetch",
            Integer.class)).isEqualTo(3);
        assertThat(applicationContext.getEnvironment().getProperty("spring.rabbitmq.virtual-host")).isEqualTo("/device");
        assertThat(applicationContext.getEnvironment().getProperty("spring.rabbitmq.username")).isEqualTo("device");
    }

    //a reading older than two minutes is stale: the meter publishes about every five seconds
    @Test
    void should_serve_a_reading_older_than_two_minutes_as_stale() {
        assertThat(meterProperties.staleAfter()).isEqualTo(Duration.ofMinutes(2));
    }

    //HAS-328: the SOLAX read every 15 s, stale after a minute (four reads missed); the usable capacity of the
    //battery of the plant as SolaxCloud gives it
    @Test
    void should_read_the_solax_every_fifteen_seconds() {
        assertThat(solaxProperties.interval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(solaxProperties.staleAfter()).isEqualTo(Duration.ofMinutes(1));
        assertThat(solaxProperties.batteryCapacity()).isEqualTo(26.5);
    }

    private String[] required(final int index) {
        return new String[] {
            applicationContext.getEnvironment().getProperty("cholewa.required-properties[" + index + "].property"),
            applicationContext.getEnvironment().getProperty("cholewa.required-properties[" + index + "].variable")
        };
    }

    //the grid meter (Shelly Pro 3EM, HAS-327) is read through the clients and the MQTT decoder of shelly-client
    @Test
    void should_have_the_clients_and_the_decoder_of_shelly_client() {
        assertThat(applicationContext.getBeanNamesForType(ShellyRpcClient.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(ShellyGen1Client.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(ShellyMqttDecoder.class)).hasSize(1);
    }
}

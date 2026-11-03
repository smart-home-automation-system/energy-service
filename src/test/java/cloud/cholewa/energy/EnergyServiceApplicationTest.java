package cloud.cholewa.energy;

import cloud.cholewa.commons.database.DatabaseProperties;
import cloud.cholewa.energy.charge.ChargeProperties;
import cloud.cholewa.energy.meter.MeterProperties;
import cloud.cholewa.energy.monitor.AvailabilityProperties;
import cloud.cholewa.energy.monitor.OverloadProperties;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.tauron.TauronProperties;
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
import java.time.LocalTime;

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
    @Autowired
    private AvailabilityProperties availabilityProperties;
    @Autowired
    private OverloadProperties overloadProperties;

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
    //the address of the grid meter (private, from the manifest), the password of the notifications (HAS-334): the start
    //is refused without any of them (cholewa.required-properties)
    @Test
    void should_require_what_has_no_default() {
        assertThat(required(0)).containsExactly("database.password", "database-password");
        assertThat(required(1)).containsExactly("spring.rabbitmq.password", "rabbitmq-password");
        assertThat(required(2)).containsExactly("notification.password", "notification-rabbitmq-password");
        assertThat(required(3)).containsExactly("energy.meter.device-id", "meter-device-id");
        assertThat(required(4)).containsExactly("energy.meter.address", "meter-address");
        //HAS-329: the application of the owner in the SolaX cloud and the plant of the house (the secret energy)
        assertThat(required(5)).containsExactly("energy.solax.cloud.client-id", "solax-client-id");
        assertThat(required(6)).containsExactly("energy.solax.cloud.client-secret", "solax-client-secret");
        assertThat(required(7)).containsExactly("energy.solax.cloud.plant-id", "solax-plant-id");
        //HAS-330: the application Solarman granted the owner and the account of the plant (the secret energy)
        assertThat(required(8)).containsExactly("energy.sofar.cloud.app-id", "solarman-app-id");
        assertThat(required(9)).containsExactly("energy.sofar.cloud.app-secret", "solarman-app-secret");
        assertThat(required(10)).containsExactly("energy.sofar.cloud.email", "solarman-email");
        assertThat(required(11)).containsExactly("energy.sofar.cloud.password-sha256", "solarman-password-sha256");
        //HAS-333: where the house is, for the sunrise and the sunset (the values of blinds-service and light-service)
        assertThat(required(12)).containsExactly("energy.location.latitude", "location-latitude");
        assertThat(required(13)).containsExactly("energy.location.longitude", "location-longitude");
        //HAS-337: the address of the house for the planned outages of Tauron (private, the manifest)
        assertThat(required(14)).containsExactly("energy.tauron.city", "tauron-city");
        assertThat(required(15)).containsExactly("energy.tauron.street", "tauron-street");
        assertThat(required(16)).containsExactly("energy.tauron.house-no", "tauron-house-no");
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.required-properties[17].property")).isNull();
    }

    //HAS-336: the charge at 5 kW plus an hour, at most 23 h, the command given two minutes; the baseline of Self Use the
    //owner runs (read in SolaxCloud on 2026-11-03) - a value lost or mistyped would set the inverter wrong after every run
    @Test
    void should_charge_and_set_back_as_agreed() {
        final ChargeProperties charge = applicationContext.getBean(ChargeProperties.class);
        assertThat(charge.powerKw()).isEqualTo(5.0);
        assertThat(charge.margin()).isEqualTo(Duration.ofHours(1));
        assertThat(charge.longest()).isEqualTo(Duration.ofHours(23));
        assertThat(charge.deliveryTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(charge.chargingAboveWatts()).isEqualTo(1000);
        assertThat(charge.baseline()).isEqualTo(new ChargeProperties.Baseline(10, true, 10, LocalTime.MIDNIGHT,
            LocalTime.of(23, 59)));
    }

    //HAS-337: Tauron read every hour for 30 days ahead, an alert after a day of failed reads, the reminders the day
    //before at 18:00 and 2 h before the start - a duration lost or mistyped would bind as milliseconds
    @Test
    void should_read_the_planned_outages_as_agreed() {
        final TauronProperties tauron =
            applicationContext.getBean(TauronProperties.class);
        assertThat(tauron.baseUrl()).hasToString("http://localhost:1");
        assertThat(tauron.interval()).isEqualTo(Duration.ofHours(1));
        assertThat(tauron.aheadDays()).isEqualTo(30);
        assertThat(tauron.alertAfter()).isEqualTo(Duration.ofHours(24));
        assertThat(tauron.reminderAt()).isEqualTo(LocalTime.of(18, 0));
        assertThat(tauron.remindBefore()).isEqualTo(Duration.ofHours(2));
    }

    //owner, 2026-11-01: Bat Power Low (17) is never told - the battery at its floor, almost every day
    @Test
    void should_leave_out_the_alarm_of_a_battery_at_its_floor() {
        assertThat(applicationContext.getBean(cloud.cholewa.energy.solaxcloud.SolaxCloudProperties.class)
            .ignoredAlarmCodes()).containsExactly("17");
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

    //HAS-332: a source silent for five minutes is an alert, reminded hourly; a phase above 22 A for three minutes warns
    //(the main fuses are 3 x 25 A) - a duration lost or mistyped would bind as milliseconds
    @Test
    void should_watch_the_sources_and_the_phases_as_agreed() {
        assertThat(availabilityProperties.solaxSilentAfter()).isEqualTo(Duration.ofMinutes(5));
        assertThat(availabilityProperties.meterSilentAfter()).isEqualTo(Duration.ofMinutes(5));
        assertThat(availabilityProperties.reminderEvery()).isEqualTo(Duration.ofHours(1));
        assertThat(overloadProperties.phaseCurrentWarn()).isEqualTo(22.0);
        assertThat(overloadProperties.overloadAfter()).isEqualTo(Duration.ofMinutes(3));
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

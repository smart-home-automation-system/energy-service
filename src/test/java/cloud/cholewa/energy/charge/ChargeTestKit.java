package cloud.cholewa.energy.charge;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.energy.automation.AutomationPlan;
import cloud.cholewa.energy.database.BatteryChargeRunEntity;
import cloud.cholewa.energy.database.BatteryChargeRunRepository;
import cloud.cholewa.energy.database.PlannedOutageRepository;
import cloud.cholewa.energy.solaxcloud.SolaxCloudClient;
import cloud.cholewa.energy.tauron.OutageLane;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

//The charge with its fakes for the tests of other packages (HAS-338): a ChargeFixture with public accessors
public final class ChargeTestKit {

    public static final LocalDateTime NOW = ChargeSamples.NOW;

    private final ChargeFixture fixture = new ChargeFixture();
    private final OutageLane outageLane = new OutageLane();

    public ChargeService service() {
        return fixture.service;
    }

    public SolaxCloudClient client() {
        return fixture.client;
    }

    public NotificationPublisher publisher() {
        return fixture.publisher;
    }

    public BatteryChargeRunRepository repository() {
        return fixture.repository;
    }

    public PlannedOutageRepository outages() {
        return fixture.outages;
    }

    public AutomationPlan plan() {
        return fixture.plan;
    }

    public OutageLane outageLane() {
        return outageLane;
    }

    public Clock clock() {
        return fixture.clock;
    }

    public Map<Long, BatteryChargeRunEntity> table() {
        return fixture.table;
    }

    public BatteryChargeRunEntity only() {
        return fixture.only();
    }

    public void moveTo(final LocalDateTime time) {
        fixture.moveTo(time);
    }

    public void battery(final Double soc, final boolean stale) {
        fixture.battery(soc, stale);
    }

    public BatteryChargeRunEntity charging() {
        return ChargeSamples.charging();
    }

    public BatteryChargeRunEntity ended() {
        return ChargeSamples.charging().withEndedAt(NOW).withEndedBy("target").withRestoredAt(NOW);
    }
}

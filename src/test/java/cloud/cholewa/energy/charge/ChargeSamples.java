package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.database.BatteryChargeRunEntity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

//The samples of the charge of the battery (HAS-336): the properties of application.yaml and a run
final class ChargeSamples {

    static final double CAPACITY = 26.5;
    //the house time of the samples: 14:05:30 on Tuesday 3 November 2026
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 14, 5, 30);
    static final ChargeProperties.Baseline BASELINE = new ChargeProperties.Baseline(10, true, 10, LocalTime.MIDNIGHT,
        LocalTime.of(23, 59));
    static final ChargeProperties PROPERTIES = new ChargeProperties(5.0, Duration.ofHours(1), Duration.ofHours(23),
        Duration.ofMinutes(2), 1000, BASELINE);

    private ChargeSamples() {
    }

    //a run of the dashboard to 100 % from 45 %, 14:05 - 19:30, its command sent
    static BatteryChargeRunEntity requested() {
        return new BatteryChargeRunEntity(1L, NOW, "dashboard", 100, NOW.withSecond(0),
            LocalDateTime.of(2026, 11, 3, 19, 30), "r-1", null, null, 45.0, null, null, null, null, null, null);
    }

    static BatteryChargeRunEntity charging() {
        return requested().withDeliveryStatus(4).withDeliveredAt(NOW.plusSeconds(20));
    }
}

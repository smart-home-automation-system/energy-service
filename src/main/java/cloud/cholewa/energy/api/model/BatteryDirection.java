package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonValue;

//Which way the energy of the battery flows, by its power with a dead band of DEAD_BAND_WATTS (HAS-328): the
//dashboard copies the words
public enum BatteryDirection {
    CHARGING("charging"),
    DISCHARGING("discharging"),
    IDLE("idle");

    public static final double DEAD_BAND_WATTS = 20.0;

    private final String wireName;

    BatteryDirection(final String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    public static BatteryDirection of(final double power) {
        if (power > DEAD_BAND_WATTS) {
            return CHARGING;
        }
        return power < -DEAD_BAND_WATTS ? DISCHARGING : IDLE;
    }
}

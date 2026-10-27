package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonValue;

//Which way the energy flows at the grid entry, by the total active power of the meter with a dead band of
//DEAD_BAND_WATTS: the dashboard copies the words
public enum GridDirection {
    IMPORT("import"),
    EXPORT("export"),
    IDLE("idle");

    public static final double DEAD_BAND_WATTS = 20.0;

    private final String wireName;

    GridDirection(final String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    public static GridDirection of(final double totalActivePower) {
        if (totalActivePower > DEAD_BAND_WATTS) {
            return IMPORT;
        }
        return totalActivePower < -DEAD_BAND_WATTS ? EXPORT : IDLE;
    }
}

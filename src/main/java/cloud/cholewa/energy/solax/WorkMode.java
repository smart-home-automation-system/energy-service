package cloud.cholewa.energy.solax;

import java.util.Arrays;

//The work mode of the SOLAX as its dongle answers it in ReadSetData (HAS-518), word 71 - pinned on the real inverter by
//three changes of the owner (2026-11-25 / 26): 160 Self Use, 161 Back Up, 164 Feed-in Priority. Any other code is a mode
//this service does not know (Manual, TOU, Peak Shaving - never seen): OTHER, which is certainly not Back Up and never
//taken for one of the three
public enum WorkMode {

    SELF_USE(160, "self use"),
    BACK_UP(161, "back up"),
    FEED_IN_PRIORITY(164, "feed-in priority"),
    OTHER(-1, "other");

    private final int code;
    private final String wireName;

    WorkMode(final int code, final String wireName) {
        this.code = code;
        this.wireName = wireName;
    }

    //the name of the API - the contract of the dashboard, pinned by SetDataTest
    public String wireName() {
        return wireName;
    }

    public static WorkMode of(final int code) {
        return Arrays.stream(values())
            .filter(mode -> mode.code == code)
            .findFirst()
            .orElse(OTHER);
    }
}

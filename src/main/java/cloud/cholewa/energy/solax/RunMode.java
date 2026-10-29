package cloud.cholewa.energy.solax;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Optional;

//The run mode of the SOLAX, Data[18] of type 33 (HAS-328), in the words the API answers - the contract of the
//dashboard, and what the grid-loss task (HAS-334) builds on: eps is the house fed from the battery. A code not
//listed here is never served; the reading logs it raw
public enum RunMode {
    WAITING(0, "waiting"),
    CHECKING(1, "checking"),
    NORMAL(2, "normal"),
    FAULT(3, "fault"),
    PERMANENT_FAULT(4, "permanent fault"),
    UPDATING(5, "updating"),
    EPS_CHECK(6, "eps check"),
    EPS(7, "eps"),
    SELF_TEST(8, "self test"),
    IDLE(9, "idle"),
    STANDBY(10, "standby");

    private final int code;
    private final String wireName;

    RunMode(final int code, final String wireName) {
        this.code = code;
        this.wireName = wireName;
    }

    public int code() {
        return code;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    static Optional<RunMode> of(final int code) {
        return Arrays.stream(values()).filter(mode -> mode.code == code).findFirst();
    }
}

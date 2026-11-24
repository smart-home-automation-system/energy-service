package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//The grid as the SOLAX sees it, in GET /home/energy/status (HAS-334): state present or lost, written out as null while
//not known (after a start, before two reads of the SOLAX agree - never read as present); since - the house time it is
//so since, left out while not known. After a start a grid that stayed present is present since the first reads.
//Since HAS-503 the frequency of the grid meter (the phase furthest from 50 Hz), left out while not known, and
//frequencyOff - whether it is outside the band now, written out as null while not known: a grid present at 51 Hz (a
//generator) is one the inverters do not connect to
public record GridStateReply(
    String state,
    @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime since,
    @JsonInclude(JsonInclude.Include.NON_NULL) Double frequency,
    @JsonInclude(JsonInclude.Include.ALWAYS) Boolean frequencyOff
) {

    public static final String PRESENT = "present";
    public static final String LOST = "lost";

    public GridStateReply(final String state, final LocalDateTime since) {
        this(state, since, null, null);
    }

    public static GridStateReply notKnown() {
        return new GridStateReply(null, null);
    }

    public GridStateReply withFrequency(final Double hz, final Boolean off) {
        return new GridStateReply(state, since, hz, off);
    }
}

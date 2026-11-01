package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//The grid as the SOLAX sees it, in GET /home/energy/status (HAS-334): state present or lost, written out as null while
//not known (after a start, before two reads of the SOLAX agree - never read as present); since - the house time it is
//so since, left out while not known. After a start a grid that stayed present is present since the first reads
public record GridStateReply(
    String state,
    @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime since
) {

    public static final String PRESENT = "present";
    public static final String LOST = "lost";

    public static GridStateReply notKnown() {
        return new GridStateReply(null, null);
    }
}

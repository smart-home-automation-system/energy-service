package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//One source of GET /home/energy/status. reachable is null while the service knows nothing of the source -
//until the task that reads it, and after a start before its first reading; written out as null, never as
//false (the dashboard shows it as not known). lastReadingAt - the house time of the newest reading of the source,
//left out while there is none (HAS-327)
public record SourceStatusReply(
    String source,
    Boolean reachable,
    @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime lastReadingAt
) {

    public static SourceStatusReply notKnown(final String source) {
        return new SourceStatusReply(source, null, null);
    }
}

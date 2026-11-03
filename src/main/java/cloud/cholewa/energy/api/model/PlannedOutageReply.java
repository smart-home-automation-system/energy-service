package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//One outage of GET /home/energy/outages/planned (HAS-337): an outage Tauron announces for the house, coming or going on,
//house times. kind - planned or unplanned (a failure); scope - street (the street of the house named) or town (the town
//alone); description - Tauron's words (which streets, in Polish); firstSeenAt - when the house first read it; notifiedAt -
//its last notice, left out until one was published; skipAutomation - no automatic charge for it (HAS-338); id - for the skip;
//plannedChargeStart - when the automation would start the charge for it, on the next outage of the street only (HAS-338)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlannedOutageReply(
    Long id,
    LocalDateTime startsAt,
    LocalDateTime endsAt,
    String kind,
    String scope,
    String description,
    LocalDateTime firstSeenAt,
    LocalDateTime notifiedAt,
    boolean skipAutomation,
    LocalDateTime plannedChargeStart
) {
}

package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//One outage of the grid of GET /home/energy/outages (HAS-334): the house times of its start and end (the first of the
//two reads of the SOLAX that confirmed each), the state of charge of the battery in % at the start, at the end and the
//lowest in between. endedAt and socAtEnd are left out while it lasts, a SOC not known is left out
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutageReply(
    LocalDateTime startedAt,
    LocalDateTime endedAt,
    Double socAtStart,
    Double socAtEnd,
    Double lowestSoc
) {
}

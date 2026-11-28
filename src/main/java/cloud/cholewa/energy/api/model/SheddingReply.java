package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//The load shedding in GET /home/energy/status (HAS-523): active always written; since - when it started, left out while it
//is off; endsAt - when it ends while the grid is back and its stable time runs, left out otherwise
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SheddingReply(
    @JsonInclude(JsonInclude.Include.ALWAYS) boolean active,
    LocalDateTime since,
    LocalDateTime endsAt
) {
}

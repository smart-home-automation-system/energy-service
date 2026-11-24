package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//GET and PUT /home/energy/battery/reserve (HAS-494) - the contract of the reserve card of the energy page (HAS-496):
//enabled - the switch (off until the owner turns it on); level - the floor the battery is held at while the grid is
//present, %; until - the end of a reserve set until a time, left out for one that holds until it is switched off;
//updatedAt - the last change, left out before the first one; writeState - what became of the last write of the
//standing Self Use: none (never written by the service), pending (to be written - within a minute, or at the end of a
//charge going on), sent (the SolaX cloud took the command), taken (the inverter is executing it), failed (refused or not
//carried out, tried again every 10 minutes); writeAttemptedAt - the last attempt, left out before the first one
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReserveReply(
    @JsonInclude(JsonInclude.Include.ALWAYS) boolean enabled,
    Integer level,
    LocalDateTime until,
    LocalDateTime updatedAt,
    String writeState,
    LocalDateTime writeAttemptedAt
) {
}

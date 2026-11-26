package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//GET and PUT /home/energy/battery/reserve (HAS-494) - the contract of the reserve card of the energy page (HAS-496):
//enabled - the switch (off until the owner turns it on); level - the floor the battery is held at while the grid is
//present, %; until - the end of a reserve set until a time, left out for one that holds until it is switched off;
//updatedAt - the last change, left out before the first one; writeState - what became of the last write of the
//standing Self Use: none (never written by the service), pending (to be written - within a minute, or at the end of a
//charge going on), sent (the SolaX cloud took the command), taken (the inverter is executing it), failed (refused or not
//carried out, tried again every 10 minutes); writeAttemptedAt - the last attempt, left out before the first one.
//Since HAS-518: inverter - the work mode and the Back Up percentages the inverter really has, read from its dongle,
//left out while not known; floorBelowLevel - true while the reserve holds and the Min SoC of Back Up read from the
//inverter is below its level (the service cannot raise it - the owner sets it by hand in the SolaX application or
//SolaxCloud), false while it is not below or no reserve holds, left out while the reserve holds and the Min SoC is not
//known.
//Since HAS-495: held - true while the reserve holds and the battery is at its level less the tolerance
//(energy.charge.soc.tolerance, 5 %) or above, false while it is below - also in an outage of the grid, where using it is
//the purpose -, left out without a reserve in force or while the state of charge is not known
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReserveReply(
    @JsonInclude(JsonInclude.Include.ALWAYS) boolean enabled,
    Integer level,
    LocalDateTime until,
    LocalDateTime updatedAt,
    String writeState,
    LocalDateTime writeAttemptedAt,
    InverterSettingsReply inverter,
    Boolean floorBelowLevel,
    Boolean held
) {

    public ReserveReply(final boolean enabled, final Integer level, final LocalDateTime until,
                        final LocalDateTime updatedAt, final String writeState, final LocalDateTime writeAttemptedAt) {
        this(enabled, level, until, updatedAt, writeState, writeAttemptedAt, null, null, null);
    }
}

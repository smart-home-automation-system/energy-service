package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//One charge of the battery from the grid (HAS-336) - the contract of the energy panel (HAS-342), house times.
//state - requested (the command sent, the inverter not yet carrying it out), charging, ending (over, the baseline of Self
//Use not set back yet), ended or failed; source - dashboard or automation; deliveryStatus - the last status of the
//command at the SolaX cloud (1 offline, 2 issuance failed, 3 issued, 4 executing, 5 failed, 6 timeout); endedBy -
//target, window, cancel or failed; failure - the error code of a failed run. What is not known yet is left out
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChargeRunReply(
    Long id,
    String state,
    String source,
    LocalDateTime requestedAt,
    Integer targetSoc,
    LocalDateTime windowStart,
    LocalDateTime windowEnd,
    Integer deliveryStatus,
    LocalDateTime deliveredAt,
    Double socAtStart,
    Double socAtEnd,
    LocalDateTime endedAt,
    String endedBy,
    String failure,
    LocalDateTime restoredAt
) {
}

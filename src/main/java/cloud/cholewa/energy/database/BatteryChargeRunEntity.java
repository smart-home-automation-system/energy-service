package cloud.cholewa.energy.database;

import lombok.With;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//A row of battery_charge_run (V11, HAS-336): one charge of the SOLAX battery from the grid, house times
@With
@Table("battery_charge_run")
public record BatteryChargeRunEntity(
    @Id Long id,
    @Column("requested_at") LocalDateTime requestedAt,
    @Column("source") String source,
    @Column("target_soc") Integer targetSoc,
    @Column("window_start") LocalDateTime windowStart,
    @Column("window_end") LocalDateTime windowEnd,
    @Column("request_id") String requestId,
    @Column("delivery_status") Integer deliveryStatus,
    @Column("delivered_at") LocalDateTime deliveredAt,
    @Column("soc_at_start") Double socAtStart,
    @Column("soc_at_end") Double socAtEnd,
    @Column("ended_at") LocalDateTime endedAt,
    @Column("ended_by") String endedBy,
    @Column("failure") String failure,
    @Column("restore_request_id") String restoreRequestId,
    @Column("restored_at") LocalDateTime restoredAt
) {

    //a run that charges or waits for its command - no end yet
    public boolean open() {
        return endedAt == null;
    }

    //a run that ended without the baseline set back yet; restoredAt is also set when nothing had to be set back (a
    //command the inverter certainly did not take)
    public boolean restorePending() {
        return endedAt != null && restoredAt == null;
    }
}

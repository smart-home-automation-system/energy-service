package cloud.cholewa.energy.database;

import lombok.With;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//A row of planned_outage (V10, HAS-337): one outage Tauron announced for the house - its id and start, house times.
//kind planned / unplanned; scope street (the street of the house named) / town (the town alone). notice - what is still
//to be told (new, changed, moved, cancelled), null when nothing is; movedFrom - the start before a move. notifiedAt - the
//last notice published; the two reminders of a planned outage of the street; closedAt / closedAs (ended, cancelled,
//moved) once it is no longer announced. skipAutomation - no automatic charge for it (HAS-338); chargeRunId - the run the
//automation started for it (V12), never a second one
@With
@Table("planned_outage")
public record PlannedOutageEntity(
    @Id Long id,
    @Column("outage_id") String outageId,
    @Column("kind") String kind,
    @Column("scope") String scope,
    @Column("starts_at") LocalDateTime startsAt,
    @Column("ends_at") LocalDateTime endsAt,
    @Column("description") String description,
    @Column("first_seen_at") LocalDateTime firstSeenAt,
    @Column("last_seen_at") LocalDateTime lastSeenAt,
    @Column("notice") String notice,
    @Column("moved_from") LocalDateTime movedFrom,
    @Column("notified_at") LocalDateTime notifiedAt,
    @Column("day_before_reminded_at") LocalDateTime dayBeforeRemindedAt,
    @Column("before_start_reminded_at") LocalDateTime beforeStartRemindedAt,
    @Column("closed_at") LocalDateTime closedAt,
    @Column("closed_as") String closedAs,
    @Column("skip_automation") boolean skipAutomation,
    @Column("charge_run_id") Long chargeRunId
) {
}

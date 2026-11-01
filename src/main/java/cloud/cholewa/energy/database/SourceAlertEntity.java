package cloud.cholewa.energy.database;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//A row of source_alert (V5, HAS-332): one episode of a source - silent, or a phase overloaded. lastToldAt null while
//its alert has not got through; closedAt null while it lasts. detail - what tells one alarm of the SOLAX from another
//(V6, HAS-329: "<error code>|<start time>|<name>"), null for the other kinds. detail - what tells one alarm of the SOLAX from another
//(V6, HAS-329: "<error code>|<start time>"), null for the other kinds
@Table("source_alert")
public record SourceAlertEntity(
    @Id Long id,
    @Column("source") String source,
    @Column("kind") String kind,
    @Column("opened_at") LocalDateTime openedAt,
    @Column("last_told_at") LocalDateTime lastToldAt,
    @Column("closed_at") LocalDateTime closedAt,
    @Column("detail") String detail
) {
}

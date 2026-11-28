package cloud.cholewa.energy.database;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//A row of load_shedding (V22, HAS-523): one episode of load shedding, with no end while it lasts. What ended it is the
//grid, back for the stable time, or the switch of the energy page turned off - none while it lasts
@Table("load_shedding")
public record LoadSheddingEntity(
    @Id Long id,
    @Column("started_at") LocalDateTime startedAt,
    @Column("ended_at") LocalDateTime endedAt,
    @Column("ended_by") String endedBy
) {

    public static final String BY_GRID = "grid";
    public static final String BY_SWITCH = "switch";

    public static LoadSheddingEntity started(final LocalDateTime at) {
        return new LoadSheddingEntity(null, at, null, null);
    }

    public LoadSheddingEntity withId(final Long rowId) {
        return new LoadSheddingEntity(rowId, startedAt, endedAt, endedBy);
    }

    public LoadSheddingEntity ended(final LocalDateTime at, final String by) {
        return new LoadSheddingEntity(id, startedAt, at, by);
    }
}

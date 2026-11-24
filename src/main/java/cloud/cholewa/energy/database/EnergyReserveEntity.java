package cloud.cholewa.energy.database;

import lombok.With;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//The one row of energy_reserve (V16, HAS-494): the battery reserve and what became of the last write of the standing Self
//Use - inserted by the migration (id 1), only ever updated
@With
@Table("energy_reserve")
public record EnergyReserveEntity(
    @Id Integer id,
    @Column("enabled") boolean enabled,
    @Column("level") Integer level,
    @Column("until_at") LocalDateTime until,
    @Column("updated_at") LocalDateTime updatedAt,
    @Column("write_outcome") String writeOutcome,
    @Column("write_request_id") String writeRequestId,
    @Column("write_attempted_at") LocalDateTime writeAttemptedAt
) {

    public static final int ROW_ID = 1;
}

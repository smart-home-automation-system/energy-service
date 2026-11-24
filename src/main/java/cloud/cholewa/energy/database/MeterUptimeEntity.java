package cloud.cholewa.energy.database;

import lombok.With;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//The one row of meter_uptime (V19, HAS-505): when the grid meter last answered its status and when it started then -
//inserted by the migration (id 1), both null until the first answer, only ever updated
@With
@Table("meter_uptime")
public record MeterUptimeEntity(
    @Id Integer id,
    @Column("seen_at") LocalDateTime seenAt,
    @Column("booted_at") LocalDateTime bootedAt
) {

    public static final int ROW_ID = 1;
}

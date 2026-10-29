package cloud.cholewa.energy.database;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//A row of battery_reading (V3, HAS-328): the battery of the SOLAX once a minute, written only when a value changed.
//soc %, power W (negative while discharging), voltage V, current A (negative while discharging), temperature °C
@Table("battery_reading")
public record BatteryReadingEntity(
    @Id Long id,
    @Column("measured_at") LocalDateTime measuredAt,
    @Column("soc") Double soc,
    @Column("power") Double power,
    @Column("voltage") Double voltage,
    @Column("current") Double current,
    @Column("temperature") Double temperature
) {
}

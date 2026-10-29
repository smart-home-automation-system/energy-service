package cloud.cholewa.energy.database;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//A row of inverter_reading (V3, HAS-328): an inverter once a minute, written only when a value changed. inverterId -
//its id in the device registry; powers W, the daily yield kWh, the temperature °C; runMode the word of the API, null
//for a code not known. A value the inverter does not give is null
@Table("inverter_reading")
public record InverterReadingEntity(
    @Id Long id,
    @Column("inverter_id") Long inverterId,
    @Column("measured_at") LocalDateTime measuredAt,
    @Column("pv_power") Double pvPower,
    @Column("pv1_power") Double pv1Power,
    @Column("pv2_power") Double pv2Power,
    @Column("ac_power") Double acPower,
    @Column("run_mode") String runMode,
    @Column("temperature") Double temperature,
    @Column("daily_yield") Double dailyYield
) {
}

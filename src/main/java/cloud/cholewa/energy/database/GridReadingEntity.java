package cloud.cholewa.energy.database;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//A row of grid_reading (V2, HAS-327): the grid meter once a minute, written only when a value changed. The
//powers are W - signed, negative where the house returns energy to the grid - the energies kWh of the counters of
//the meter, the voltages V. A value the meter did not send is null. measured_at is the house time the readings
//arrived at
@Table("grid_reading")
public record GridReadingEntity(
    @Id Long id,
    @Column("measured_at") LocalDateTime measuredAt,
    @Column("a_power") Double aPower,
    @Column("b_power") Double bPower,
    @Column("c_power") Double cPower,
    @Column("total_power") Double totalPower,
    @Column("import_energy") Double importEnergy,
    @Column("export_energy") Double exportEnergy,
    @Column("a_voltage") Double aVoltage,
    @Column("b_voltage") Double bVoltage,
    @Column("c_voltage") Double cVoltage
) {
}

package cloud.cholewa.energy.database;

import lombok.With;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//The one row of energy_automation (V12, HAS-338): the settings of the automatic charge before a planned outage - inserted
//by the migration (id 1), only ever updated
@With
@Table("energy_automation")
public record EnergyAutomationEntity(
    @Id Integer id,
    @Column("charge_before_outage") boolean chargeBeforeOutage,
    @Column("target_soc") Integer targetSoc,
    @Column("charge_power_kw") Double chargePowerKw,
    @Column("margin_minutes") Integer marginMinutes,
    @Column("updated_at") LocalDateTime updatedAt
) {

    public static final int ROW_ID = 1;
}

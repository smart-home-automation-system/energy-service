package cloud.cholewa.energy.database;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

//A row of energy_day (V8, HAS-331): one day of the house, written once it is over. kWh; selfConsumption and gridShare
//0..1; the cloud columns the cross-check of the SolaX cloud. A value not established is null. The day is the key, so
//the row is always new (Persistable): Spring Data would take a set key for an update
@Table("energy_day")
public record EnergyDayEntity(
    @Id @Column("day") LocalDate day,
    @Column("solax_yield") Double solaxYield,
    @Column("sofar_yield") Double sofarYield,
    @Column("production") Double production,
    @Column("imported") Double imported,
    @Column("exported") Double exported,
    @Column("charged") Double charged,
    @Column("discharged") Double discharged,
    @Column("house_load") Double houseLoad,
    @Column("self_consumption") Double selfConsumption,
    @Column("grid_share") Double gridShare,
    @Column("cloud_yield") Double cloudYield,
    @Column("cloud_imported") Double cloudImported,
    @Column("cloud_exported") Double cloudExported,
    @Column("closed_at") LocalDateTime closedAt
) implements Persistable<LocalDate> {

    @Override
    public LocalDate getId() {
        return day;
    }

    @Override
    public boolean isNew() {
        return true;
    }
}

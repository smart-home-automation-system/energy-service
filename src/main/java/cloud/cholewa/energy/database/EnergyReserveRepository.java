package cloud.cholewa.energy.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface EnergyReserveRepository extends ReactiveCrudRepository<EnergyReserveEntity, Integer> {
}

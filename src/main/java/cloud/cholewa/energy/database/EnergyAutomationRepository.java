package cloud.cholewa.energy.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface EnergyAutomationRepository extends ReactiveCrudRepository<EnergyAutomationEntity, Integer> {
}

package cloud.cholewa.energy.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface BatteryReadingRepository extends ReactiveCrudRepository<BatteryReadingEntity, Long> {
}

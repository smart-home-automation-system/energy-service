package cloud.cholewa.energy.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface MeterUptimeRepository extends ReactiveCrudRepository<MeterUptimeEntity, Integer> {
}

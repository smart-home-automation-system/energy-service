package cloud.cholewa.energy.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface InverterReadingRepository extends ReactiveCrudRepository<InverterReadingEntity, Long> {
}

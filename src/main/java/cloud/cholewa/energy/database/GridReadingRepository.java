package cloud.cholewa.energy.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface GridReadingRepository extends ReactiveCrudRepository<GridReadingEntity, Long> {
}

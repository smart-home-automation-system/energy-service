package cloud.cholewa.energy.database;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

public interface EnergyDayRepository extends ReactiveCrudRepository<EnergyDayEntity, LocalDate> {

    //the days of [from, to], oldest first
    Flux<EnergyDayEntity> findByDayBetweenOrderByDay(LocalDate from, LocalDate to);

    //every day up to "to", oldest first - the settlement carries the credit of every month before
    Flux<EnergyDayEntity> findByDayLessThanEqualOrderByDay(LocalDate to);

    @Query("SELECT max(day) FROM energy_day")
    Mono<LocalDate> findLastDay();
}

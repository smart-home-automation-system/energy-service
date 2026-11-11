package cloud.cholewa.energy.database;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

//An hour of elicznik_hour (V15, HAS-340): the hour of the portal ending at "hour" (1-24), repeated - the first
//02:00 - 03:00 of the night the clocks go back; kWh taken from and given to the grid as the meter of the operator counts
//them. Written through ElicznikRepository - the key has three columns, which Spring Data R2DBC does not map
public record ElicznikHourEntity(
    LocalDate day,
    int hour,
    boolean repeated,
    BigDecimal imported,
    BigDecimal exported,
    LocalDateTime fetchedAt
) {
}

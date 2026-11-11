package cloud.cholewa.energy.database;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

//A row of elicznik_meter (V15, HAS-340): the registers of the meter of the operator at the end of a day, whole kWh
public record ElicznikMeterEntity(
    LocalDate day,
    BigDecimal imported,
    BigDecimal exported,
    LocalDateTime fetchedAt
) {
}

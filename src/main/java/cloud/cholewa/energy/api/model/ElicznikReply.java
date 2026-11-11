package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

//GET /home/energy/elicznik?from=&to= (HAS-340): the days the eLicznik portal of Tauron served whole - the meter of the
//operator, the truth of the net-metering. kWh.
//days - each stored day of the range, by the day:
//  imported, exported - the sums of its hours; meterImported, meterExported - the registers of the meter at its end, whole
//  kWh, left out when not stored;
//  hours - hour 1-24, the hour ENDING at it (1 is 00:00 - 01:00, house time); repeated true for the first 02:00 - 03:00 of
//  the night the clocks go back (25 hours that day, 23 the night they go forward - no hour 3)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ElicznikReply(
    LocalDate from,
    LocalDate to,
    List<Day> days
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Day(
        LocalDate day,
        BigDecimal imported,
        BigDecimal exported,
        BigDecimal meterImported,
        BigDecimal meterExported,
        List<Hour> hours
    ) {
    }

    public record Hour(
        int hour,
        boolean repeated,
        BigDecimal imported,
        BigDecimal exported
    ) {
    }
}

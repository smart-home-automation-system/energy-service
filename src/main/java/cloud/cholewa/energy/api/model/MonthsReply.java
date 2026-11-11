package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

//GET /home/energy/months?year= (HAS-331): the billing year of the house - the twelve months from energy.settlement
//.period-start of "year" - with the net-metering in kWh, never money (the owner's "system opustów", not net-billing).
//ratio - the share of an exported kWh credited; from, to - the first and the last month of the billing year
//months - each month of the year, also one without a closed day (days 0, its energies left out):
//  days - the closed days of the month; production, imported, exported, houseLoad - their sums, kWh (left out without a
//  day); credited = exported x ratio; drawn - the credit the imports of the month used, oldest first; bought - the imports
//  the credit did not cover; expired - the credit of a month twelve months back that lapsed; stored - the credit left at
//  the end of the month, what may still be drawn back within its twelve months.
//The settlement is the house's estimate by whole months, from the first day the service closed - not the invoice.
//elicznikImported, elicznikExported, elicznikCredited, elicznikBalance (HAS-340) - the same from the days the eLicznik
//portal of Tauron served (the meter of the operator): the sums of the month, its credit and the credit left at its end,
//settled from the first month the portal was read; left out for a month without such a day
public record MonthsReply(
    int year,
    YearMonth from,
    YearMonth to,
    BigDecimal ratio,
    List<Month> months
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Month(
        YearMonth month,
        int days,
        BigDecimal production,
        BigDecimal imported,
        BigDecimal exported,
        BigDecimal houseLoad,
        BigDecimal credited,
        BigDecimal drawn,
        BigDecimal bought,
        BigDecimal expired,
        BigDecimal stored,
        BigDecimal elicznikImported,
        BigDecimal elicznikExported,
        BigDecimal elicznikCredited,
        BigDecimal elicznikBalance
    ) {
    }
}

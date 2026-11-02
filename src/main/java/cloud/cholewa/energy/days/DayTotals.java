package cloud.cholewa.energy.days;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.stream.Stream;

//The energy of a day of the house (HAS-331), without I/O - pinned by DayTotalsTest; what a closed day stores and what
//GET /flows answers for today. kWh: production of both inverters, imported from and exported to the grid (the meter),
//charged into and discharged from the battery (the SOLAX).
//houseLoad = production + imported - exported - charged + discharged - what the house used, wherever it came from.
//selfConsumption = (production - exported) / production - the share of the production used in the house (stored in the
//battery included); gridShare = imported / houseLoad - the share of the house load taken from the grid. Both 0..1,
//rounded to 3 decimals, the energies to 2. Any input not known makes what depends on it not known (null), never 0; a
//divisor of 0 leaves the share out
public record DayTotals(
    BigDecimal production,
    BigDecimal imported,
    BigDecimal exported,
    BigDecimal charged,
    BigDecimal discharged
) {

    private static final int KWH_SCALE = 2;
    private static final int SHARE_SCALE = 3;

    //null when any of its inputs is not known
    public BigDecimal houseLoad() {
        return Stream.of(production, imported, exported, charged, discharged).anyMatch(value -> value == null) ? null
            : production.add(imported).subtract(exported).subtract(charged).add(discharged)
                .setScale(KWH_SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal selfConsumption() {
        return Optional.ofNullable(production)
            .filter(value -> exported != null && value.signum() > 0)
            .map(value -> share(value.subtract(exported), value))
            .orElse(null);
    }

    public BigDecimal gridShare() {
        return Optional.ofNullable(houseLoad())
            .filter(load -> imported != null && load.signum() > 0)
            .map(load -> share(imported, load))
            .orElse(null);
    }

    private static BigDecimal share(final BigDecimal part, final BigDecimal whole) {
        return part.divide(whole, SHARE_SCALE, RoundingMode.HALF_UP).max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    //the sum of two daily productions; null when either is not known
    public static BigDecimal sum(final BigDecimal first, final BigDecimal second) {
        return first == null || second == null ? null : first.add(second).setScale(KWH_SCALE, RoundingMode.HALF_UP);
    }
}

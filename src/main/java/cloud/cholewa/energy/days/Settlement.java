package cloud.cholewa.energy.days;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

//The net-metering of the house month by month (HAS-331), without I/O - pinned by SettlementTest. In kWh, never money
//(owner, 2026-10-23): what a month exports is credited at the ratio of the agreement; the credit of a month may be drawn
//back in that month and the eleven after it, then it expires; what a month imports is drawn from the oldest credit first,
//and what the credit does not cover is bought. A model by whole months - Tauron settles by its own periods, so the numbers
//are the house's estimate, not the invoice. Only the months the service recorded count: before its first closed day there
//is no credit
final class Settlement {

    static final int MONTHS_VALID = 12;
    private static final int SCALE = 2;

    private Settlement() {
    }

    //the energy of a month as the closed days sum it up; imported and exported 0 for a month without a day
    record MonthEnergy(YearMonth month, BigDecimal imported, BigDecimal exported) {
    }

    //credited - exported x ratio; drawn - the credit the imports used; bought - the imports the credit did not cover;
    //expired - the credit that lapsed at the start of the month; stored - the credit left at its end
    record MonthSettlement(YearMonth month, BigDecimal credited, BigDecimal drawn, BigDecimal bought, BigDecimal expired,
                           BigDecimal stored) {
    }

    private static final class Lot {
        private final YearMonth month;
        private BigDecimal left;

        private Lot(final YearMonth month, final BigDecimal left) {
            this.month = month;
            this.left = left;
        }
    }

    //the months in order, without gaps
    static List<MonthSettlement> settle(final List<MonthEnergy> months, final BigDecimal ratio) {
        final Deque<Lot> lots = new ArrayDeque<>();
        final List<MonthSettlement> settled = new ArrayList<>();
        for (final MonthEnergy month : months) {
            BigDecimal expired = BigDecimal.ZERO;
            while (!lots.isEmpty() && !lots.peekFirst().month.plusMonths(MONTHS_VALID).isAfter(month.month())) {
                expired = expired.add(lots.pollFirst().left);
            }
            final BigDecimal credited = month.exported().multiply(ratio).setScale(SCALE, RoundingMode.HALF_UP);
            lots.addLast(new Lot(month.month(), credited));
            BigDecimal toDraw = month.imported();
            BigDecimal drawn = BigDecimal.ZERO;
            while (toDraw.signum() > 0 && !lots.isEmpty()) {
                final Lot oldest = lots.peekFirst();
                final BigDecimal taken = oldest.left.min(toDraw);
                oldest.left = oldest.left.subtract(taken);
                toDraw = toDraw.subtract(taken);
                drawn = drawn.add(taken);
                if (oldest.left.signum() == 0) {
                    lots.pollFirst();
                }
            }
            final BigDecimal stored = lots.stream().map(lot -> lot.left).reduce(BigDecimal.ZERO, BigDecimal::add);
            settled.add(new MonthSettlement(month.month(), credited, scaled(drawn), scaled(toDraw), scaled(expired),
                scaled(stored)));
        }
        return settled;
    }

    private static BigDecimal scaled(final BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}

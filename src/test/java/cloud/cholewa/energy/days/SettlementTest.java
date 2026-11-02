package cloud.cholewa.energy.days;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//the net-metering month by month (HAS-331): an exported kWh credited at 0.7, the credit usable in its month and the
//eleven after, the imports drawing from the oldest credit first
class SettlementTest {

    private static final BigDecimal RATIO = new BigDecimal("0.7");

    private static Settlement.MonthEnergy month(final int year, final int month, final String imported,
                                                final String exported) {
        return new Settlement.MonthEnergy(YearMonth.of(year, month), new BigDecimal(imported), new BigDecimal(exported));
    }

    @Test
    void should_credit_the_export_and_draw_the_import_from_it() {
        final List<Settlement.MonthSettlement> settled = Settlement.settle(List.of(
            month(2026, 6, "100", "500"),
            month(2026, 7, "150", "100")), RATIO);

        assertThat(settled.get(0)).isEqualTo(new Settlement.MonthSettlement(YearMonth.of(2026, 6),
            new BigDecimal("350.00"), new BigDecimal("100.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
            new BigDecimal("250.00")));
        assertThat(settled.get(1)).isEqualTo(new Settlement.MonthSettlement(YearMonth.of(2026, 7),
            new BigDecimal("70.00"), new BigDecimal("150.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
            new BigDecimal("170.00")));
    }

    //a winter month imports more than the credit holds: the rest is bought
    @Test
    void should_buy_what_the_credit_does_not_cover() {
        final List<Settlement.MonthSettlement> settled = Settlement.settle(List.of(
            month(2026, 11, "0", "100"),
            month(2026, 12, "400", "10")), RATIO);

        assertThat(settled.get(1).drawn()).isEqualByComparingTo("77");
        assertThat(settled.get(1).bought()).isEqualByComparingTo("323");
        assertThat(settled.get(1).stored()).isEqualByComparingTo("0");
    }

    //the credit of June 2026 may be drawn up to May 2027 and lapses at the start of June 2027
    @Test
    void should_let_the_credit_expire_after_twelve_months() {
        final List<Settlement.MonthEnergy> months = new ArrayList<>();
        months.add(month(2026, 6, "0", "100"));
        for (YearMonth month = YearMonth.of(2026, 7); !month.isAfter(YearMonth.of(2027, 6)); month = month.plusMonths(1)) {
            months.add(new Settlement.MonthEnergy(month, BigDecimal.ZERO, BigDecimal.ZERO));
        }

        final List<Settlement.MonthSettlement> settled = Settlement.settle(months, RATIO);

        assertThat(settled.get(11).month()).isEqualTo(YearMonth.of(2027, 5));
        assertThat(settled.get(11).stored()).isEqualByComparingTo("70");
        assertThat(settled.get(12).month()).isEqualTo(YearMonth.of(2027, 6));
        assertThat(settled.get(12).expired()).isEqualByComparingTo("70");
        assertThat(settled.get(12).stored()).isEqualByComparingTo("0");
    }

    //the oldest credit first: what is drawn in spring is the credit that would lapse first
    @Test
    void should_draw_the_oldest_credit_first() {
        final List<Settlement.MonthEnergy> months = new ArrayList<>();
        months.add(month(2026, 6, "0", "100"));
        months.add(month(2026, 7, "0", "100"));
        for (YearMonth month = YearMonth.of(2026, 8); !month.isAfter(YearMonth.of(2027, 5)); month = month.plusMonths(1)) {
            months.add(new Settlement.MonthEnergy(month, BigDecimal.ZERO, BigDecimal.ZERO));
        }
        months.set(months.size() - 1, month(2027, 5, "50", "0"));
        months.add(month(2027, 6, "0", "0"));

        final List<Settlement.MonthSettlement> settled = Settlement.settle(months, RATIO);

        assertThat(settled.getLast().month()).isEqualTo(YearMonth.of(2027, 6));
        assertThat(settled.getLast().expired()).isEqualByComparingTo("20");
        assertThat(settled.getLast().stored()).isEqualByComparingTo("70");
    }
}

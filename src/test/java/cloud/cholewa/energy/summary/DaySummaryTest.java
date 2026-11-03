package cloud.cholewa.energy.summary;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

//the words of the daily energy summary (HAS-339): every line of a day with everything known, a value not known said so
//and never 0, a silent source said instead of its numbers, the month with and without today
class DaySummaryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 3, 21, 30);
    private static final DaySummary.Month MONTH = new DaySummary.Month(YearMonth.of(2026, 11), bd("41.0"),
        bd("17.7"), bd("0.7"), true);

    private static BigDecimal bd(final String value) {
        return new BigDecimal(value);
    }

    private static DaySummary day(final DaySummary.Month month, final boolean solaxSilent, final boolean meterSilent) {
        return new DaySummary(AT, bd("11.24"), bd("7.2"), bd("18.44"),
            new DaySummary.Peak(LocalTime.of(12, 0), bd("4912.5")), bd("21.7"), bd("0.821"), bd("6.1"), bd("3.3"),
            bd("8.0"), bd("7.6"), bd("64.4"), month, solaxSilent, meterSilent);
    }

    @Test
    void should_tell_the_day_with_everything_known() {
        assertThat(day(MONTH, false, false).text()).isEqualTo("""
            Energy on Tuesday 3 November (until 21:30)
            Production 18.4 kWh - SOLAX 11.2, SOFAR 7.2; most between 12:00 and 13:00 (4.9 kW on average)
            House load 21.7 kWh, self-consumption 82 %
            Grid: imported 6.1 kWh, exported 3.3 kWh
            Battery: charged 8.0 kWh, discharged 7.6 kWh, now 64 %
            November so far: credited 12.4 kWh (0.7 x 17.7 exported), imported 41.0 kWh - balance -28.6 kWh""");
    }

    @Test
    void should_say_not_known_instead_of_a_number() {
        final DaySummary unknown = new DaySummary(AT, null, null, null, null, null, null, null, null, null, null, null,
            null, false, false);

        assertThat(unknown.text()).isEqualTo("""
            Energy on Tuesday 3 November (until 21:30)
            Production not known - SOLAX not known, SOFAR not known
            House load not known, self-consumption not known
            Grid: imported not known, exported not known
            Battery: charged not known, discharged not known, now not known
            The month: not known""");
    }

    @Test
    void should_say_a_source_was_silent_instead_of_its_numbers() {
        final String text = day(MONTH, true, true).text();

        assertThat(text).contains("Production not known - SOLAX no readings for most of the day, SOFAR 7.2")
            .contains("House load not known, self-consumption not known")
            .contains("Grid: the grid meter had no readings for most of the day")
            .contains("Battery: the SOLAX had no readings for most of the day, now 64 %");
    }

    @Test
    void should_count_the_month_up_to_yesterday_without_today_and_mark_a_credit_with_a_plus() {
        final DaySummary.Month month = new DaySummary.Month(YearMonth.of(2026, 11), bd("2.0"), bd("10.0"), bd("0.7"),
            false);

        assertThat(day(month, false, false).text()).endsWith(
            "November up to yesterday: credited 7.0 kWh (0.7 x 10.0 exported), imported 2.0 kWh - balance +5.0 kWh");
    }
}

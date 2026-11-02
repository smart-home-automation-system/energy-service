package cloud.cholewa.energy.days;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

//the energy of a day (HAS-331): what the house used, the share of the production it used and the share of its load from
//the grid; an input not known leaves out what depends on it, never 0
class DayTotalsTest {

    private static BigDecimal kwh(final String value) {
        return new BigDecimal(value);
    }

    //a sunny day: 30 kWh produced, 12 exported, 3 imported, 8 into the battery and 6 back out of it
    @Test
    void should_balance_a_day() {
        final DayTotals day = new DayTotals(kwh("30"), kwh("3"), kwh("12"), kwh("8"), kwh("6"));

        assertThat(day.houseLoad()).isEqualByComparingTo("19.00");
        assertThat(day.selfConsumption()).isEqualByComparingTo("0.600");
        assertThat(day.gridShare()).isEqualByComparingTo("0.158");
    }

    //a winter night: nothing produced - no self-consumption, all from the grid and the battery
    @Test
    void should_leave_the_self_consumption_out_without_a_production() {
        final DayTotals day = new DayTotals(kwh("0"), kwh("10"), kwh("0"), kwh("0"), kwh("5"));

        assertThat(day.houseLoad()).isEqualByComparingTo("15.00");
        assertThat(day.selfConsumption()).isNull();
        assertThat(day.gridShare()).isEqualByComparingTo("0.667");
    }

    @Test
    void should_leave_out_what_depends_on_an_input_not_known() {
        final DayTotals day = new DayTotals(kwh("30"), null, kwh("12"), kwh("8"), kwh("6"));

        assertThat(day.houseLoad()).isNull();
        assertThat(day.gridShare()).isNull();
        assertThat(day.selfConsumption()).isEqualByComparingTo("0.600");
    }

    //a share stays within 0..1 even when the counters of different devices disagree a little
    @Test
    void should_keep_a_share_within_its_bounds() {
        final DayTotals day = new DayTotals(kwh("10"), kwh("0"), kwh("10.2"), kwh("0"), kwh("0"));

        assertThat(day.selfConsumption()).isEqualByComparingTo("0");
    }

    @Test
    void should_sum_two_productions_only_when_both_are_known() {
        assertThat(DayTotals.sum(kwh("1.5"), kwh("2"))).isEqualByComparingTo("3.5");
        assertThat(DayTotals.sum(kwh("1.5"), null)).isNull();
    }
}

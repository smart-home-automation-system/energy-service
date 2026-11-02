package cloud.cholewa.energy.history;

import cloud.cholewa.energy.api.model.HistoryReply;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//the sum of stored values (HAS-331): a part without a row in a bucket keeps its last value - a row is stored only after a
//change; no point before every part has a value
class SumSeriesTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 2, 10, 0);
    private static final LocalDateTime T1 = T0.plusMinutes(5);
    private static final LocalDateTime T2 = T0.plusMinutes(10);

    private static HistoryReply.Point point(final LocalDateTime at, final String avg) {
        return new HistoryReply.Point(at, new BigDecimal(avg), new BigDecimal(avg), new BigDecimal(avg));
    }

    private static HistoryReply.Point sum(final LocalDateTime at, final String avg) {
        return new HistoryReply.Point(at, new BigDecimal(avg), null, null);
    }

    @Test
    void should_add_the_parts_with_their_signs() {
        final List<HistoryReply.Point> sums = SumSeries.sum(List.of(
            new SumSeries.Part(1, null, List.of(point(T0, "1000"), point(T1, "1200.5"))),
            new SumSeries.Part(-1, null, List.of(point(T0, "-300"), point(T1, "400")))));

        assertThat(sums).containsExactly(sum(T0, "1300.00"), sum(T1, "800.50"));
    }

    //the SOFAR stores a row every five minutes at most: in between its last value stands
    @Test
    void should_carry_a_part_that_did_not_change() {
        final List<HistoryReply.Point> sums = SumSeries.sum(List.of(
            new SumSeries.Part(1, null, List.of(point(T0, "100"), point(T1, "110"), point(T2, "120"))),
            new SumSeries.Part(1, null, List.of(point(T0, "200")))));

        assertThat(sums).containsExactly(sum(T0, "300.00"), sum(T1, "310.00"), sum(T2, "320.00"));
    }

    //a part with no row in the range stands on its last value before it
    @Test
    void should_start_a_part_from_its_value_before_the_range() {
        final List<HistoryReply.Point> sums = SumSeries.sum(List.of(
            new SumSeries.Part(1, null, List.of(point(T1, "100"))),
            new SumSeries.Part(1, new BigDecimal("0"), List.of())));

        assertThat(sums).containsExactly(sum(T1, "100.00"));
    }

    @Test
    void should_have_no_point_before_every_part_has_a_value() {
        final List<HistoryReply.Point> sums = SumSeries.sum(List.of(
            new SumSeries.Part(1, null, List.of(point(T0, "100"), point(T2, "150"))),
            new SumSeries.Part(1, null, List.of(point(T1, "50")))));

        assertThat(sums).containsExactly(sum(T1, "150.00"), sum(T2, "200.00"));
    }

    @Test
    void should_answer_nothing_without_rows() {
        assertThat(SumSeries.sum(List.of(new SumSeries.Part(1, BigDecimal.TEN, List.of())))).isEmpty();
    }
}

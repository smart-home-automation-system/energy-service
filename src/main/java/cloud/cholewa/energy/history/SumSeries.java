package cloud.cholewa.energy.history;

import cloud.cholewa.energy.api.model.HistoryReply;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

//The buckets of a sum of stored metrics (pv-power, house-load; HAS-331), without I/O - pinned by SumSeriesTest. A row is
//stored only after a change, so a part without a row in a bucket still has its last value: each part is carried from its
//last bucket, or from its last reading before the range. A bucket of the sum is the signed sum of the averages of its
//parts, once every part has a value - before that it has no point; and a bucket only where at least one part has a row.
//The sum has no min or max (the extremes of the parts need not meet)
final class SumSeries {

    private SumSeries() {
    }

    //one part: its sign, its value before the range (null when none) and its buckets, oldest first
    record Part(int sign, BigDecimal before, List<HistoryReply.Point> points) {
    }

    static List<HistoryReply.Point> sum(final List<Part> parts) {
        final List<Map<LocalDateTime, BigDecimal>> averages = parts.stream()
            .map(part -> {
                final Map<LocalDateTime, BigDecimal> byTime = new TreeMap<>();
                part.points().forEach(point -> byTime.put(point.at(), point.avg()));
                return byTime;
            })
            .toList();
        final TreeSet<LocalDateTime> times = new TreeSet<>();
        averages.forEach(byTime -> times.addAll(byTime.keySet()));
        final BigDecimal[] last = parts.stream().map(Part::before).toArray(BigDecimal[]::new);
        final List<HistoryReply.Point> sums = new ArrayList<>();
        for (final LocalDateTime at : times) {
            BigDecimal total = BigDecimal.ZERO;
            boolean known = true;
            for (int index = 0; index < parts.size(); index++) {
                final BigDecimal value = averages.get(index).get(at);
                if (value != null) {
                    last[index] = value;
                }
                known = known && last[index] != null;
                total = last[index] == null ? total
                    : total.add(last[index].multiply(BigDecimal.valueOf(parts.get(index).sign())));
            }
            if (known) {
                sums.add(new HistoryReply.Point(at, total.setScale(2, RoundingMode.HALF_UP), null, null));
            }
        }
        return sums;
    }
}

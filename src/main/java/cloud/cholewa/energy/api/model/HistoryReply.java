package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code GET /home/energy/history} (HAS-331): one metric over a range, in buckets of one width - the rules of the
 * hot-water and weather history.<br>
 * {@code metric} - the name of the metric as it was asked for.<br>
 * {@code from}, {@code to} - the range as it was asked for; it includes its start and not its end.<br>
 * {@code bucketSeconds} - the width of a bucket, chosen by the service from the length of the range.<br>
 * {@code points} - one per bucket that has a reading, oldest first. A row is stored only after a change, so a bucket
 * without a point of a stored metric is a value that did not change, or no reading; two points further apart than
 * {@code bucketSeconds} are such a gap.<br>
 * Every date-time is wall-clock time of the house, as the readings are stored.
 */
public record HistoryReply(
    String metric,
    LocalDateTime from,
    LocalDateTime to,
    long bucketSeconds,
    List<Point> points
) {

    /**
     * {@code at} - the start of the bucket, aligned to the clock of the house, not to the range.<br>
     * {@code avg}, {@code min}, {@code max} - over the stored readings of the bucket within the range, the average
     * rounded to 2 decimals. A sum ({@code pv-power}, {@code house-load}) has the average only: the sum of the averages
     * of its parts, each part carried from its last reading while it did not change, a point only once every part has
     * one.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Point(LocalDateTime at, BigDecimal avg, BigDecimal min, BigDecimal max) {
    }
}

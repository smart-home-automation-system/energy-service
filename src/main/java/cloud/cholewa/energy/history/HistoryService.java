package cloud.cholewa.energy.history;

import cloud.cholewa.energy.api.model.HistoryReply;
import cloud.cholewa.energy.error.EnergyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

//GET /home/energy/history (HAS-331): the stored readings of one metric from "from" up to, not including, "to", in buckets
//whose width follows from the length of the range (HistoryRange). A name that is no metric is UNKNOWN_METRIC, a range that
//breaks a rule INVALID_RANGE; a metric of an inverter needs the registry (REGISTRY_UNAVAILABLE before its first read), and
//an inverter the registry does not have answers no points - a sum with such a part too. A sum reads every part with its
//value before the range in one query and adds them up (SumSeries). One range at a time (OneAtATime), each cut off after
//QUERY_TIMEOUT
@Service
public class HistoryService {

    //a query that does not answer would hold every later one (R2DBC has no statement timeout); a month answers in well
    //under a second - a sum asks one query for all its parts
    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);

    private final StoredValues storedValues;
    private final OneAtATime oneAtATime = new OneAtATime();

    public HistoryService(final StoredValues storedValues) {
        this.storedValues = storedValues;
    }

    public Mono<HistoryReply> history(final String metricName, final LocalDateTime from, final LocalDateTime to) {
        return Mono.justOrEmpty(Metric.ofApiName(metricName))
            .switchIfEmpty(Mono.error(() -> EnergyException.unknownMetric(Metric.apiNames())))
            .flatMap(metric -> Mono.justOrEmpty(HistoryRange.violation(from, to))
                .flatMap(Mono::<Metric>error)
                .defaultIfEmpty(metric))
            .flatMap(metric -> {
                final long bucketSeconds = HistoryRange.bucket(from, to).toSeconds();
                return oneAtATime.run(points(metric, from, to, bucketSeconds).timeout(QUERY_TIMEOUT))
                    .map(points -> new HistoryReply(metric.apiName(), from, to, bucketSeconds, points));
            });
    }

    private Mono<List<HistoryReply.Point>> points(final Metric metric, final LocalDateTime from, final LocalDateTime to,
                                                  final long bucketSeconds) {
        return Mono.just(metric)
            .filter(Metric::isSum)
            .flatMap(sum -> storedValues.sum(sum.parts(), from, to, bucketSeconds)
                .map(SumSeries::sum)
                .defaultIfEmpty(List.of()))
            .switchIfEmpty(Mono.defer(() -> storedValues.history(metric.parts().getFirst().value(), from, to,
                bucketSeconds).collectList()));
    }
}

package cloud.cholewa.energy.history;

import cloud.cholewa.energy.api.model.HistoryReply;
import cloud.cholewa.energy.error.EnergyError;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.registry.InverterRegistry;
import cloud.cholewa.energy.solax.SolaxSamples;
import cloud.cholewa.home.model.InverterBrand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the history of a metric (HAS-331): a stored metric as its query answers it, a sum of its parts with their values before
//the range, the inverters by their registry ids, and the refusals with their codes
class HistoryServiceTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 11, 2, 0, 0);
    private static final LocalDateTime TO = FROM.plusDays(1);

    private final HistoryRepository repository = mock(HistoryRepository.class);
    private final InverterRegistry registry = mock(InverterRegistry.class);
    private final HistoryService service = new HistoryService(new StoredValues(repository, registry));

    private static HistoryReply.Point point(final LocalDateTime at, final String avg) {
        return new HistoryReply.Point(at, new BigDecimal(avg), new BigDecimal(avg), new BigDecimal(avg));
    }

    @BeforeEach
    void setUp() {
        when(registry.current()).thenReturn(Optional.of(List.of(SolaxSamples.solax("192.0.2.158"), SolaxSamples.sofar())));
        when(registry.of(InverterBrand.SOLAX)).thenReturn(Optional.of(SolaxSamples.solax("192.0.2.158")));
        when(registry.of(InverterBrand.SOFAR)).thenReturn(Optional.of(SolaxSamples.sofar()));
        when(repository.findLastBefore(any(), any(), any())).thenReturn(Mono.empty());
        when(repository.findHistory(any(), any(), any(), any(), anyLong())).thenReturn(Flux.empty());
    }

    //a range of a day: buckets of 5 minutes
    @Test
    void should_answer_a_stored_metric_in_its_buckets() {
        when(repository.findHistory(StoredValue.GRID_POWER, null, FROM, TO, 300))
            .thenReturn(Flux.just(point(FROM, "1200")));

        service.history("grid-power", FROM, TO).as(StepVerifier::create)
            .expectNext(new HistoryReply("grid-power", FROM, TO, 300, List.of(point(FROM, "1200"))))
            .verifyComplete();
    }

    @Test
    void should_read_an_inverter_by_its_registry_id() {
        service.history("pv-sofar", FROM, TO).as(StepVerifier::create).expectNextCount(1).verifyComplete();

        verify(repository).findHistory(StoredValue.PV_SOFAR, 135L, FROM, TO, 300);
    }

    //house-load = pv-solax + pv-sofar + grid-power - battery-power, the SOFAR carried from before the range
    @Test
    void should_sum_the_house_load_of_its_parts() {
        when(repository.findHistory(eq(StoredValue.PV_SOLAX), eq(SolaxSamples.ID), any(), any(), anyLong()))
            .thenReturn(Flux.just(point(FROM, "1000")));
        when(repository.findLastBefore(StoredValue.PV_SOFAR, 135L, FROM)).thenReturn(Mono.just(new BigDecimal("200")));
        when(repository.findHistory(eq(StoredValue.GRID_POWER), isNull(), any(), any(), anyLong()))
            .thenReturn(Flux.just(point(FROM, "-300")));
        when(repository.findHistory(eq(StoredValue.BATTERY_POWER), isNull(), any(), any(), anyLong()))
            .thenReturn(Flux.just(point(FROM, "400")));

        service.history("house-load", FROM, TO).as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.points()).containsExactly(
                new HistoryReply.Point(FROM, new BigDecimal("500.00"), null, null)))
            .verifyComplete();
    }

    //a sum with a part of an inverter the registry does not have: no points, never a partial sum
    @Test
    void should_answer_no_sum_without_one_of_its_inverters() {
        when(registry.of(InverterBrand.SOFAR)).thenReturn(Optional.empty());
        when(repository.findHistory(eq(StoredValue.PV_SOLAX), any(), any(), any(), anyLong()))
            .thenReturn(Flux.just(point(FROM, "1000")));

        service.history("pv-power", FROM, TO).as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.points()).isEmpty())
            .verifyComplete();
    }

    @Test
    void should_refuse_a_metric_not_known_naming_the_ones_there_are() {
        service.history("solar-wind", FROM, TO).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(EnergyException.class, refused -> {
                assertThat(refused.getError()).isEqualTo(EnergyError.UNKNOWN_METRIC);
                assertThat(refused.getMessage()).startsWith("The metric is one of grid-power, ")
                    .doesNotContain("solar-wind");
            }))
            .verify();
    }

    @Test
    void should_refuse_a_range_that_breaks_a_rule() {
        service.history("grid-power", TO, FROM).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((EnergyException) failure).getError())
                .isEqualTo(EnergyError.INVALID_RANGE))
            .verify();

        verify(repository, never()).findHistory(any(), any(), any(), any(), anyLong());
    }

    //a metric of an inverter before the registry was read; one of the meter needs no registry
    @Test
    void should_refuse_an_inverter_metric_while_the_registry_is_not_known() {
        when(registry.current()).thenReturn(Optional.empty());

        service.history("pv-solax", FROM, TO).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((EnergyException) failure).getError())
                .isEqualTo(EnergyError.REGISTRY_UNAVAILABLE))
            .verify();
        service.history("grid-power", FROM, TO).as(StepVerifier::create).expectNextCount(1).verifyComplete();
    }
}

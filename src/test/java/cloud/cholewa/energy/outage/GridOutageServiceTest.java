package cloud.cholewa.energy.outage;

import cloud.cholewa.energy.api.model.OutageReply;
import cloud.cholewa.energy.database.GridOutageEntity;
import cloud.cholewa.energy.database.GridOutageRepository;
import cloud.cholewa.energy.error.EnergyError;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.solax.SolaxTracker;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//the outages of the API (HAS-334)
class GridOutageServiceTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 1, 0, 0);

    private final GridOutageRepository repository = mock(GridOutageRepository.class);
    private final GridOutageService service = new GridOutageService(repository, mock(GridOutageWatch.class),
        mock(SolaxTracker.class), mock(SolaxProperties.class), Clock.systemDefaultZone());

    @Test
    void should_answer_the_outages_that_started_in_the_range() {
        final LocalDateTime to = FROM.plusDays(366);
        when(repository.findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAt(FROM, to))
            .thenReturn(Flux.just(
                new GridOutageEntity(1L, FROM.plusDays(3), FROM.plusDays(3).plusHours(2), 80.0, 52.0, 52.0,
                    FROM.plusDays(3).plusHours(1), null),
                new GridOutageEntity(2L, FROM.plusDays(30), null, 64.0, null, 18.0, FROM.plusDays(30), null)));

        service.outages(FROM, to)
            .as(StepVerifier::create)
            .expectNext(List.of(
                new OutageReply(FROM.plusDays(3), FROM.plusDays(3).plusHours(2), 80.0, 52.0, 52.0),
                new OutageReply(FROM.plusDays(30), null, 64.0, null, 18.0)))
            .verifyComplete();
    }

    //a silent SOLAX: the grid present by its last reads is not known now; an open outage stays lost, as stored
    @Test
    void should_not_know_the_grid_while_the_solax_is_stale() {
        final GridWatch present = GridWatch.loaded(null).presentSince(FROM);
        final GridWatch lost = GridWatch.loaded(new GridOutageEntity(1L, FROM, null, 50.0, null, 50.0, FROM, null));

        assertThat(GridOutageService.stateOf(present, true).state()).isNull();
        assertThat(GridOutageService.stateOf(present, false).state()).isEqualTo("present");
        assertThat(GridOutageService.stateOf(lost, true).state()).isEqualTo("lost");
    }

    @ParameterizedTest
    @CsvSource({"0", "-1", "367"})
    void should_refuse_a_range_that_is_empty_reversed_or_too_long(final long days) {
        service.outages(FROM, FROM.plusDays(days))
            .as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error)
                .isInstanceOf(EnergyException.class)
                .extracting(failure -> ((EnergyException) failure).getError())
                .isEqualTo(EnergyError.INVALID_RANGE))
            .verify();
        verifyNoInteractions(repository);
    }
}

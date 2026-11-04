package cloud.cholewa.energy.forecast;

import cloud.cholewa.energy.database.PvForecastEntity;
import cloud.cholewa.energy.database.PvForecastRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the fetch of the forecast (HAS-371): both planes one after the other, only the hours not yet started written, a failure
//of one plane keeping the other, the state of each, "now" read inside the chain, a hung pass ended by its bound
class ForecastFetchTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final ForecastProperties.Plane ROOF = new ForecastProperties.Plane(35.0, 0.0, 8.24);
    private static final ForecastProperties.Plane GROUND = new ForecastProperties.Plane(37.0, 0.0, 5.0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 10, 0, 10);

    private final Instant[] now = {NOW.atZone(HOUSE).toInstant()};
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return HOUSE;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now[0];
        }
    };
    private final OpenMeteoClient client = mock(OpenMeteoClient.class);
    private final PvForecastRepository repository = mock(PvForecastRepository.class);
    private final ForecastState state = new ForecastState();
    private ForecastFetch fetch;

    private static HourForecast hour(final int hour, final double irradiance) {
        return new HourForecast(LocalDateTime.of(2026, 11, 4, hour, 0), irradiance, 1.0, 50.0, 5.0, 0.0);
    }

    @BeforeEach
    void setUp() {
        fetch = new ForecastFetch(client, repository, state, new ForecastProperties(URI.create("http://localhost:1"),
            Duration.ofHours(1), ROOF, GROUND), clock);
        when(repository.save(any())).thenReturn(Mono.just(1L));
    }

    @Test
    void should_store_the_hours_not_yet_started_of_both_planes() {
        when(client.forecast(ROOF)).thenReturn(Mono.just(List.of(hour(9, 40), hour(10, 60), hour(11, 80))));
        when(client.forecast(GROUND)).thenReturn(Mono.just(List.of(hour(11, 90), hour(12, 70))));

        fetch.fetch().as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<PvForecastEntity> rows = ArgumentCaptor.forClass(PvForecastEntity.class);
        verify(repository, times(3)).save(rows.capture());
        assertThat(rows.getAllValues()).containsExactly(
            new PvForecastEntity(LocalDateTime.of(2026, 11, 4, 11, 0), "solax", 80.0, 1.0, 50.0, 5.0, 0.0, NOW),
            new PvForecastEntity(LocalDateTime.of(2026, 11, 4, 11, 0), "sofar", 90.0, 1.0, 50.0, 5.0, 0.0, NOW),
            new PvForecastEntity(LocalDateTime.of(2026, 11, 4, 12, 0), "sofar", 70.0, 1.0, 50.0, 5.0, 0.0, NOW));
        assertThat(state.of("solax")).contains(new ForecastState.Plane(NOW, false));
        assertThat(state.of("sofar")).contains(new ForecastState.Plane(NOW, false));
    }

    @Test
    void should_keep_the_other_plane_when_one_fails() {
        when(client.forecast(ROOF)).thenReturn(Mono.error(new OpenMeteoException("Open-Meteo answered HTTP 502")));
        when(client.forecast(GROUND)).thenReturn(Mono.just(List.of(hour(12, 70))));

        fetch.fetch().as(StepVerifier::create).verifyComplete();

        verify(repository).save(any());
        assertThat(state.of("solax")).contains(new ForecastState.Plane(null, true));
        assertThat(state.of("sofar")).contains(new ForecastState.Plane(NOW, false));
    }

    @Test
    void should_keep_the_last_fetch_of_a_plane_that_fails_later() {
        when(client.forecast(any())).thenReturn(Mono.just(List.of(hour(12, 70))));
        fetch.fetch().as(StepVerifier::create).verifyComplete();

        when(client.forecast(GROUND)).thenReturn(Mono.error(new OpenMeteoException("Open-Meteo answered HTTP 502")));
        when(repository.save(any())).thenReturn(Mono.error(new IllegalStateException("database down")));
        now[0] = now[0].plus(Duration.ofHours(1));
        fetch.fetch().as(StepVerifier::create).verifyComplete();

        assertThat(state.of("solax")).contains(new ForecastState.Plane(NOW, true));
        assertThat(state.of("sofar")).contains(new ForecastState.Plane(NOW, true));
    }

    @Test
    void should_mark_a_plane_failed_when_its_hours_cannot_be_stored() {
        when(client.forecast(any())).thenReturn(Mono.just(List.of(hour(12, 70))));
        when(repository.save(any())).thenReturn(Mono.error(new IllegalStateException("INSERT INTO pv_forecast ... failing row")));

        fetch.fetch().as(StepVerifier::create).verifyComplete();

        assertThat(state.of("solax")).contains(new ForecastState.Plane(null, true));
        assertThat(state.of("sofar")).contains(new ForecastState.Plane(null, true));
    }

    //the pass is built once and subscribed again by every run: the hours of the second run are judged by its own time
    @Test
    void should_read_the_time_inside_the_chain() {
        when(client.forecast(ROOF)).thenReturn(Mono.just(List.of(hour(11, 80))));
        when(client.forecast(GROUND)).thenReturn(Mono.just(List.of()));
        final Mono<Void> pass = fetch.fetch();

        pass.as(StepVerifier::create).verifyComplete();
        now[0] = now[0].plus(Duration.ofHours(1));
        pass.as(StepVerifier::create).verifyComplete();

        verify(repository, times(1)).save(any());
    }

    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        doReturn(Mono.never()).when(client).forecast(any());

        StepVerifier.withVirtualTime(fetch::fetch)
            .expectSubscription()
            .expectNoEvent(ForecastFetch.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }
}

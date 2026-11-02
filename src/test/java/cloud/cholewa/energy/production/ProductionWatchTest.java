package cloud.cholewa.energy.production;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.energy.api.model.InverterReply;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
import cloud.cholewa.energy.inverter.InverterService;
import cloud.cholewa.energy.solax.RunMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the watch of the production (HAS-333) on its collaborators: a low inverter counted pass by pass and told after the
//silence, the SOLAX in EPS or a stale one never judged, a station not known never sunny, a row only once told
class ProductionWatchTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 28, 12, 0);

    private final Instant[] now = {NOON.atZone(HOUSE).toInstant()};
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
    private final SunCalendar sunCalendar = mock(SunCalendar.class);
    private final WeatherClient weatherClient = mock(WeatherClient.class);
    private final InverterService inverterService = mock(InverterService.class);
    private final SourceAlertRepository repository = mock(SourceAlertRepository.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final ProductionWatch watch = new ProductionWatch(sunCalendar, weatherClient, inverterService, repository,
        publisher, new ProductionProperties(Duration.ofMinutes(15), 20000, 100, Duration.ofHours(1)), clock);

    private static InverterReply inverter(final String id, final RunMode runMode, final double pv, final boolean stale) {
        return new InverterReply(id, 134L, id, "garage", runMode, null, null, null, null, pv, null, null, null, null,
            null, null, null, NOON, stale, "local");
    }

    private void passes(final int minutes) {
        for (int minute = 0; minute <= minutes; minute++) {
            watch.check().as(StepVerifier::create).verifyComplete();
            now[0] = now[0].plus(Duration.ofMinutes(1));
        }
    }

    @BeforeEach
    void setUp() {
        when(sunCalendar.of(any())).thenReturn(new SunCalendar.SunDay(NOON.minusHours(5), NOON.plusHours(4)));
        when(weatherClient.illuminance()).thenReturn(Mono.just(new WeatherClient.Illuminance(24500, NOON, false)));
        when(repository.findByClosedAtIsNullAndKindIn(List.of("production"))).thenReturn(Flux.empty());
        when(repository.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
        when(publisher.publishReminder(anyString())).thenReturn(Mono.empty());
    }

    @Test
    void should_alert_of_an_inverter_low_in_the_sun_for_the_silence() {
        when(inverterService.inverters()).thenReturn(Mono.just(List.of(inverter("solax", RunMode.NORMAL, 4200, false),
            inverter("sofar", null, 20, false))));

        passes(15);

        verify(publisher).publishAlert("SOFAR produces nothing in the sun: 20 W at 24500 lx since 12:00.");
        verify(repository).save(argThat(row -> row.source().equals("sofar") && row.kind().equals("production")));
    }

    @Test
    void should_not_alert_before_the_silence() {
        when(inverterService.inverters()).thenReturn(Mono.just(List.of(inverter("sofar", null, 20, false))));

        passes(14);

        verify(publisher, never()).publishAlert(anyString());
    }

    //the outage alert covers a lost grid, the availability alert a silent source
    @Test
    void should_not_judge_a_solax_in_eps_nor_a_stale_inverter() {
        when(inverterService.inverters()).thenReturn(Mono.just(List.of(inverter("solax", RunMode.EPS, 0, false),
            inverter("sofar", null, 0, true))));

        passes(20);

        verify(publisher, never()).publishAlert(anyString());
    }

    @Test
    void should_never_see_the_sun_without_the_station() {
        when(weatherClient.illuminance()).thenReturn(Mono.error(new WeatherClient.WeatherCallException("weather-service answered 503")));
        when(inverterService.inverters()).thenReturn(Mono.just(List.of(inverter("sofar", null, 0, false))));

        passes(20);

        verify(publisher, never()).publishAlert(anyString());
    }

    @Test
    void should_never_see_the_sun_from_a_stale_station_or_at_night() {
        when(weatherClient.illuminance()).thenReturn(Mono.just(new WeatherClient.Illuminance(24500, NOON, true)));
        when(inverterService.inverters()).thenReturn(Mono.just(List.of(inverter("sofar", null, 0, false))));
        passes(20);

        when(weatherClient.illuminance()).thenReturn(Mono.just(new WeatherClient.Illuminance(24500, NOON, false)));
        when(sunCalendar.of(any())).thenReturn(new SunCalendar.SunDay(NOON.plusHours(10), NOON.plusHours(20)));
        passes(20);

        verify(publisher, never()).publishAlert(anyString());
    }

    @Test
    void should_store_nothing_when_the_alert_was_not_published() {
        when(inverterService.inverters()).thenReturn(Mono.just(List.of(inverter("sofar", null, 20, false))));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.error(new IllegalStateException("broker down")));

        passes(15);

        verify(repository, never()).save(any());
    }

    @Test
    void should_tell_the_production_again_and_close() {
        when(repository.findByClosedAtIsNullAndKindIn(List.of("production"))).thenReturn(Flux.just(
            new SourceAlertEntity(5L, "sofar", "production", NOON.minusHours(1), NOON.minusMinutes(45), null, null)));
        when(inverterService.inverters()).thenReturn(Mono.just(List.of(inverter("sofar", null, 2500, false))));

        watch.check().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishInfo("SOFAR produces again: 2500 W - from 11:00 to 12:00 (1 h 0 min).");
        verify(repository).save(argThat(row -> row.id() == 5L && row.closedAt() != null));
    }

    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        doReturn(Mono.never()).when(inverterService).inverters();

        StepVerifier.withVirtualTime(watch::check)
            .expectSubscription()
            .expectNoEvent(ProductionWatch.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }
}

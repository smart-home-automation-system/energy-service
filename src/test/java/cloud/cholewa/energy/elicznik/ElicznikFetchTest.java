package cloud.cholewa.energy.elicznik;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.energy.database.ElicznikHourEntity;
import cloud.cholewa.energy.database.ElicznikMeterEntity;
import cloud.cholewa.energy.database.ElicznikRepository;
import cloud.cholewa.energy.database.EnergyDayEntity;
import cloud.cholewa.energy.database.EnergyDayRepository;
import cloud.cholewa.energy.error.EnergyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static cloud.cholewa.energy.elicznik.ElicznikSamples.CONSUM_2026_11_08;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.CONSUM_2026_11_10;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.OZE_2026_11_08;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.READINGS_EXPORTED;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.READINGS_IMPORTED;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the fetch of the eLicznik portal (HAS-340): no login when nothing is missing, one login for every day and register
//missing, a day not whole asked again, a day compared with the 3EM, the alert after alert-after of failed mornings and
//the info of their return, the fetch by hand refused within fetch-every
class ElicznikFetchTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    //06:00 of the house on 9 November: yesterday the 8th, retry-days 7 back to the 2nd
    private static final Instant MORNING = Instant.parse("2026-11-09T05:00:00Z");
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 11, 8);
    private static final LocalDate FIRST = LocalDate.of(2026, 11, 2);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ElicznikClient client = mock(ElicznikClient.class);
    private final ElicznikRepository repository = mock(ElicznikRepository.class);
    private final EnergyDayRepository energyDays = mock(EnergyDayRepository.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(MORNING);
    private final ElicznikClient.Session session = new ElicznikClient.Session(new CookieJar());
    private ElicznikFetch sut;

    @BeforeEach
    void setUp() {
        final Clock clock = new Clock() {
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
                return now.get();
            }
        };
        sut = new ElicznikFetch(client, repository, energyDays, publisher,
            ElicznikClientTest.properties("http://localhost:1"), clock);
        when(repository.save(any(ElicznikHourEntity.class))).thenReturn(Mono.just(1L));
        when(repository.save(any(ElicznikMeterEntity.class))).thenReturn(Mono.just(1L));
        when(energyDays.findById(any(LocalDate.class))).thenReturn(Mono.empty());
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
    }

    //every day of the window stored whole but "missing", with every register stored
    private void stored(final List<LocalDate> missingDays) {
        when(repository.hoursADay(FIRST, YESTERDAY)).thenReturn(Flux.fromStream(FIRST.datesUntil(YESTERDAY.plusDays(1))
            .filter(day -> !missingDays.contains(day))
            .map(day -> new ElicznikRepository.DayHours(day, 24))));
        when(repository.meterDays(FIRST, YESTERDAY)).thenReturn(Flux.fromStream(FIRST.datesUntil(YESTERDAY.plusDays(1))));
    }

    private List<ElicznikAnswers.Hour> hours(final String sample) {
        return ElicznikAnswers.hours(jsonMapper, read(sample));
    }

    @Test
    void should_not_log_in_when_nothing_is_missing() {
        stored(List.of());

        sut.morning().as(StepVerifier::create).verifyComplete();

        verify(client, never()).login();
    }

    @Test
    void should_store_the_day_missing_whole_with_one_login() {
        stored(List.of(YESTERDAY));
        when(client.login()).thenReturn(Mono.just(session));
        when(client.hours(session, YESTERDAY, false)).thenReturn(Mono.just(hours(CONSUM_2026_11_08)));
        when(client.hours(session, YESTERDAY, true)).thenReturn(Mono.just(hours(OZE_2026_11_08)));

        sut.byHand().as(StepVerifier::create).expectNext(new ElicznikFetch.Outcome(1, 0, 1)).verifyComplete();

        verify(client, times(1)).login();
        verify(repository, times(24)).save(any(ElicznikHourEntity.class));
    }

    //the registers of every day missing, from both lists, by the day
    @Test
    void should_store_the_registers_missing() {
        when(repository.hoursADay(FIRST, YESTERDAY)).thenReturn(Flux.fromStream(FIRST.datesUntil(YESTERDAY.plusDays(1))
            .map(day -> new ElicznikRepository.DayHours(day, 24))));
        when(repository.meterDays(FIRST, YESTERDAY)).thenReturn(Flux.fromStream(FIRST.datesUntil(LocalDate.of(2026, 11, 7))));
        when(client.login()).thenReturn(Mono.just(session));
        when(client.registers(session, LocalDate.of(2026, 11, 7), YESTERDAY, false))
            .thenReturn(Mono.just(ElicznikAnswers.registers(jsonMapper, read(READINGS_IMPORTED))));
        when(client.registers(session, LocalDate.of(2026, 11, 7), YESTERDAY, true))
            .thenReturn(Mono.just(ElicznikAnswers.registers(jsonMapper, read(READINGS_EXPORTED))));

        sut.byHand().as(StepVerifier::create).expectNext(new ElicznikFetch.Outcome(0, 2, 1)).verifyComplete();

        verify(repository).save(new ElicznikMeterEntity(LocalDate.of(2026, 11, 7), new BigDecimal("2760"),
            new BigDecimal("4872"), LocalDateTime.of(2026, 11, 9, 6, 0)));
        verify(repository).save(new ElicznikMeterEntity(YESTERDAY, new BigDecimal("2777"), new BigDecimal("4872"),
            LocalDateTime.of(2026, 11, 9, 6, 0)));
    }

    //a day the portal does not serve whole yet: nothing stored, asked again the next morning
    @Test
    void should_store_nothing_of_a_day_not_whole() {
        stored(List.of(YESTERDAY));
        when(client.login()).thenReturn(Mono.just(session));
        when(client.hours(eq(session), eq(YESTERDAY), anyBoolean())).thenReturn(Mono.just(hours(CONSUM_2026_11_10)));

        sut.byHand().as(StepVerifier::create).expectNext(new ElicznikFetch.Outcome(0, 0, 1)).verifyComplete();

        verify(repository, never()).save(any(ElicznikHourEntity.class));
    }

    @Test
    void should_compare_a_day_with_the_3em() {
        stored(List.of(YESTERDAY));
        when(client.login()).thenReturn(Mono.just(session));
        when(client.hours(session, YESTERDAY, false)).thenReturn(Mono.just(hours(CONSUM_2026_11_08)));
        when(client.hours(session, YESTERDAY, true)).thenReturn(Mono.just(hours(OZE_2026_11_08)));
        when(energyDays.findById(YESTERDAY)).thenReturn(Mono.just(new EnergyDayEntity(YESTERDAY, null, null, null,
            20.0, 0.6, null, null, null, null, null, null, null, null, LocalDateTime.of(2026, 11, 9, 0, 20))));

        sut.byHand().as(StepVerifier::create).expectNextCount(1).verifyComplete();

        verify(energyDays).findById(YESTERDAY);
    }

    @Test
    void should_measure_how_far_apart_two_days_are() {
        assertThat(ElicznikFetch.share(new BigDecimal("17.223"), new BigDecimal("20.0"))).get()
            .satisfies(apart -> assertThat(apart).isEqualByComparingTo("0.1389"));
        assertThat(ElicznikFetch.share(BigDecimal.ZERO, BigDecimal.ZERO)).isEmpty();
    }

    //the run of failed mornings: a WARN each, the alert at alert-after (3 days), never twice, the info at the return
    @Test
    void should_alert_after_three_days_of_failures_and_tell_the_return() {
        when(repository.hoursADay(any(), any())).thenReturn(Flux.empty());
        when(repository.meterDays(any(), any())).thenReturn(Flux.empty());
        when(client.login()).thenReturn(Mono.error(new ElicznikException(
            "The eLicznik portal refused the login - the password expired or the account changed")));

        for (int morning = 0; morning < 3; morning++) {
            now.set(MORNING.plus(Duration.ofDays(morning)));
            sut.morning().as(StepVerifier::create).verifyComplete();
        }
        verify(publisher, never()).publishAlert(anyString());

        now.set(MORNING.plus(Duration.ofDays(3)));
        sut.morning().as(StepVerifier::create).verifyComplete();
        now.set(MORNING.plus(Duration.ofDays(4)));
        sut.morning().as(StepVerifier::create).verifyComplete();

        verify(publisher, times(1)).publishAlert(
            "The eLicznik readings of Tauron not fetched since 2026-11-09 - the portal changed or the password expired: "
                + "The eLicznik portal refused the login - the password expired or the account changed");

        when(client.login()).thenReturn(Mono.just(session));
        when(client.hours(any(), any(), anyBoolean())).thenReturn(Mono.just(List.of()));
        when(client.registers(any(), any(), any(), anyBoolean())).thenReturn(Mono.just(List.of()));
        now.set(MORNING.plus(Duration.ofDays(5)));
        sut.morning().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishInfo("The eLicznik readings of Tauron are fetched again (failing since 2026-11-09).");
    }

    //by hand: a failure answered as ELICZNIK_UNAVAILABLE, a second login within fetch-every refused
    @Test
    void should_answer_a_failure_by_hand_and_refuse_a_second_login_too_soon() {
        stored(List.of(YESTERDAY));
        when(client.login()).thenReturn(Mono.just(session));
        when(client.hours(eq(session), eq(YESTERDAY), anyBoolean()))
            .thenReturn(Mono.error(new ElicznikException("The eLicznik portal answered without success")));

        sut.byHand().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOf(EnergyException.class)
                .hasMessage("The eLicznik portal answered without success"))
            .verify();

        now.set(MORNING.plus(Duration.ofMinutes(30)));
        sut.byHand().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOf(EnergyException.class)
                .hasMessage("The eLicznik portal was logged in to at 06:00 - not again before 07:00"))
            .verify();
        verify(client, times(1)).login();
    }

    @Test
    void should_end_a_morning_that_never_completes_at_its_bound() {
        stored(List.of(YESTERDAY));
        when(client.login()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(sut::morning)
            .thenAwait(ElicznikFetch.LIMIT.plusSeconds(1))
            .verifyComplete();
    }
}

package cloud.cholewa.energy.tauron;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.energy.database.PlannedOutageEntity;
import cloud.cholewa.energy.database.PlannedOutageRepository;
import cloud.cholewa.energy.solax.SolaxSamples;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the watch of the planned outages (HAS-337) with a fake Tauron, a table in memory and a clock moved on: the first read
//announces, the reminders come the day before at 18:00 and 2 h before the start, a read that fails for 24 h is an alert
class PlannedOutageWatchTest {

    private final SolaxSamples.MovableClock clock = new SolaxSamples.MovableClock();
    private final TauronClient client = mock(TauronClient.class);
    private final PlannedOutageRepository repository = mock(PlannedOutageRepository.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final Map<Long, PlannedOutageEntity> table = new TreeMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final PlannedOutageWatch watch = new PlannedOutageWatch(client, repository, publisher,
        TauronSamples.properties(URI.create("http://localhost:1")), clock);

    @BeforeEach
    void setUp() {
        moveTo(TauronSamples.NOW);
        when(client.cities("Testowo")).thenReturn(Mono.just(SolaxSamples.JSON.readTree(
            "[{\"GAID\":105,\"Name\":\"Testowo\"}]")));
        when(client.streets("Przykładowa", 105)).thenReturn(Mono.just(SolaxSamples.JSON.readTree(
            "[{\"GAID\":751,\"Name\":\"Przykładowa\",\"ShortName\":\"Przykładowa\"}]")));
        when(client.outages(any(), any(), any())).thenReturn(Mono.fromCallable(TauronSamples::areaTree));
        when(repository.save(any())).thenAnswer(call -> {
            final PlannedOutageEntity row = call.getArgument(0);
            final PlannedOutageEntity saved = row.id() == null ? row.withId(ids.incrementAndGet()) : row;
            table.put(saved.id(), saved);
            return Mono.just(saved);
        });
        when(repository.findByClosedAtIsNull()).thenAnswer(call -> Flux.fromStream(table.values().stream()
            .filter(row -> row.closedAt() == null)));
        when(repository.findByClosedAtIsNullOrNoticeIsNotNull()).thenAnswer(call -> Flux.fromStream(
            table.values().stream().filter(row -> row.closedAt() == null || row.notice() != null)));
        when(publisher.publishAlertSms(anyString())).thenReturn(Mono.empty());
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
        when(publisher.publishReminderSms(anyString())).thenReturn(Mono.empty());
    }

    private void moveTo(final LocalDateTime time) {
        clock.moveBy(Duration.between(clock.instant(), time.atZone(SolaxSamples.HOUSE).toInstant()));
    }

    private void pass() {
        watch.watch().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_announce_what_concerns_the_house_once() {
        pass();

        verify(publisher).publishAlert("Failure of the grid at the street of the house (Tauron), power expected back"
            + " at Tue 3 Nov 18:30. Testowo ul. Przykładowa i okolice");
        verify(publisher).publishAlertSms("Planned outage of the grid (Tauron): Thu 12 Nov 08:00-13:00."
            + " Testowo ul. Lipowa, Przykladowa,  Długa");
        verify(publisher).publishAlertSms("Planned outage of the grid (Tauron): Fri 13 Nov 08:00-13:00."
            + " Testowo ul. Lipowa, Przykladowa,  Długa");
        verify(publisher).publishInfo("Planned outage in the town (Tauron), the street of the house not named:"
            + " Wed 18 Nov 08:00-14:00. TESTOWO Ul: Sobieskiego");
        assertThat(table.values()).hasSize(4).allSatisfy(row -> {
            assertThat(row.notice()).isNull();
            assertThat(row.notifiedAt()).isEqualTo(TauronSamples.NOW);
        });

        clearInvocations(publisher);
        moveTo(TauronSamples.NOW.plusHours(1));
        pass();

        verify(publisher, never()).publishAlertSms(anyString());
        verify(publisher, never()).publishInfo(anyString());
        verify(publisher, never()).publishAlert(anyString());
        //the address is resolved once
        verify(client, times(1)).cities(anyString());
        verify(client, times(1)).streets(anyString(), anyLong());
        verify(client, times(2)).outages(any(), any(), any());
    }

    @Test
    void should_read_tauron_once_an_interval_and_tell_every_minute() {
        pass();
        moveTo(TauronSamples.NOW.plusMinutes(59));
        pass();

        verify(client, times(1)).outages(any(), any(), any());
        verify(repository, times(2)).findByClosedAtIsNullOrNoticeIsNotNull();
    }

    //the DoD of HAS-337: the reminders verified with a fake outage and a clock moved on
    @Test
    void should_remind_the_evening_before_and_two_hours_before_the_start() {
        pass();
        clearInvocations(publisher);

        moveTo(LocalDateTime.of(2026, 11, 11, 17, 59));
        pass();
        verify(publisher, never()).publishReminderSms(anyString());

        moveTo(LocalDateTime.of(2026, 11, 11, 18, 0));
        pass();
        verify(publisher).publishReminderSms(
            "Reminder: the grid is off tomorrow, Thu 12 Nov 08:00-13:00 (Tauron, planned).");

        moveTo(LocalDateTime.of(2026, 11, 11, 18, 1));
        pass();
        verify(publisher, times(1)).publishReminderSms(anyString());

        moveTo(LocalDateTime.of(2026, 11, 12, 6, 0));
        pass();
        verify(publisher).publishReminderSms(
            "Reminder: the grid goes off at 08:00, Thu 12 Nov 08:00-13:00 (Tauron, planned).");
        verify(publisher, times(2)).publishReminderSms(anyString());
    }

    @Test
    void should_tell_again_with_the_next_pass_a_notice_that_was_not_published() {
        when(publisher.publishAlertSms(anyString())).thenReturn(Mono.error(new IllegalStateException("broker down")));
        pass();

        assertThat(table.values()).filteredOn(row -> "planned".equals(row.kind()) && "street".equals(row.scope()))
            .hasSize(2)
            .allSatisfy(row -> assertThat(row.notice()).isEqualTo("new"));

        when(publisher.publishAlertSms(anyString())).thenReturn(Mono.empty());
        moveTo(TauronSamples.NOW.plusMinutes(1));
        pass();

        assertThat(table.values()).allSatisfy(row -> assertThat(row.notice()).isNull());
        verify(publisher, times(4)).publishAlertSms(anyString());
    }

    //owner, 2026-11-03: a cancelled outage of the street is an info without an SMS
    @Test
    void should_tell_the_cancellation_of_an_outage_announced_before() {
        pass();
        when(client.outages(any(), any(), any())).thenReturn(Mono.just(SolaxSamples.JSON.readTree(
            "{\"IdsWWW\":[999],\"OutageItems\":[]}")));
        moveTo(TauronSamples.NOW.plusHours(1));
        pass();
        //one answer without them may be a hiccup of Tauron
        verify(publisher, never()).publishInfo(startsWith("Planned outage of the grid cancelled"));
        moveTo(TauronSamples.NOW.plusHours(2));
        pass();

        verify(publisher).publishInfo("Planned outage of the grid cancelled (Tauron): Thu 12 Nov 08:00-13:00 is no"
            + " longer announced.");
        verify(publisher).publishInfo("Planned outage of the grid cancelled (Tauron): Fri 13 Nov 08:00-13:00 is no"
            + " longer announced.");
        assertThat(table.values()).allSatisfy(row -> {
            assertThat(row.closedAt()).isNotNull();
            assertThat(row.notice()).isNull();
        });
    }

    @Test
    void should_alert_once_after_a_day_of_failed_reads_and_tell_their_return() {
        when(client.cities(anyString())).thenReturn(Mono.error(new TauronException("Tauron answered HTTP 503")));
        pass();
        moveTo(TauronSamples.NOW.plusHours(23));
        pass();
        verify(publisher, never()).publishAlert(anyString());

        moveTo(TauronSamples.NOW.plusHours(24));
        pass();
        moveTo(TauronSamples.NOW.plusHours(25));
        pass();
        verify(publisher, times(1)).publishAlert("Tauron's announcements of planned outages not read since 15:00"
            + " 2026-11-03 - a planned outage would not be known.");

        when(client.cities(anyString())).thenReturn(Mono.just(SolaxSamples.JSON.readTree(
            "[{\"GAID\":105,\"Name\":\"Testowo\"}]")));
        moveTo(TauronSamples.NOW.plusHours(26));
        pass();
        verify(publisher).publishInfo("Tauron's announcements of planned outages are read again (not read since"
            + " 15:00 2026-11-03).");
    }

    @Test
    void should_read_again_with_the_next_pass_when_the_database_did_not_take_the_rows() {
        when(repository.findByClosedAtIsNull()).thenReturn(Flux.error(new IllegalStateException("database down")));
        pass();
        moveTo(TauronSamples.NOW.plusMinutes(1));
        pass();

        verify(client, times(2)).outages(any(), any(), any());
    }

    //a notice published but not stored is stored first by the next pass - never told twice
    @Test
    void should_store_a_told_row_the_database_did_not_take_before_telling_anything() {
        doAnswer(call -> {
            final PlannedOutageEntity row = call.getArgument(0);
            if (row.id() != null && row.notifiedAt() != null) {
                return Mono.error(new IllegalStateException("database down"));
            }
            final PlannedOutageEntity saved = row.id() == null ? row.withId(ids.incrementAndGet()) : row;
            table.put(saved.id(), saved);
            return Mono.just(saved);
        }).when(repository).save(any());
        pass();
        verify(publisher, times(1)).publishAlert(anyString());

        doAnswer(call -> {
            final PlannedOutageEntity row = call.getArgument(0);
            table.put(row.id(), row);
            return Mono.just(row);
        }).when(repository).save(any());
        moveTo(TauronSamples.NOW.plusMinutes(1));
        pass();

        verify(publisher, times(1)).publishAlert(anyString());
        assertThat(table.values()).allSatisfy(row -> assertThat(row.notice()).isNull());
    }

    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        when(client.cities(anyString())).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(watch::watch)
            .expectSubscription()
            .expectNoEvent(PlannedOutageWatch.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
        assertThat(Duration.ofMinutes(1)).isGreaterThan(PlannedOutageWatch.LIMIT);
    }

    @Test
    void should_ask_for_the_days_ahead_from_now() {
        pass();

        verify(client).outages(new TauronAddress(105, 751, "1a"), TauronSamples.NOW, TauronSamples.NOW.plusDays(30));
        assertThat(List.copyOf(table.values())).isNotEmpty();
    }
}

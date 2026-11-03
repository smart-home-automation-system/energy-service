package cloud.cholewa.energy.summary;

import cloud.cholewa.commons.notification.NotificationPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the watch of the daily summary (HAS-339): nothing before the time, one info a day from it on, a failure tried again
//with the next pass and given up after SEND_WITHIN, a start after the time sends nothing that day, a hung pass ended
class SummaryWatchTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final LocalDateTime EVENING = LocalDateTime.of(2026, 11, 3, 21, 0);
    private static final SummaryProperties PROPERTIES = new SummaryProperties(LocalTime.of(21, 30));

    private final Instant[] now = {EVENING.atZone(HOUSE).toInstant()};
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
    private final SummaryService summaryService = mock(SummaryService.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final DaySummary summary = new DaySummary(EVENING, null, null, null, null, null, null, null, null, null,
        null, null, null, false, false);
    private final String text = summary.text();

    private SummaryWatch watch() {
        return new SummaryWatch(summaryService, publisher, PROPERTIES, clock);
    }

    private void at(final int hour, final int minute) {
        now[0] = EVENING.withHour(hour).withMinute(minute).plusSeconds(7).atZone(HOUSE).toInstant();
    }

    private void nextDay() {
        now[0] = now[0].plus(Duration.ofDays(1));
    }

    @BeforeEach
    void setUp() {
        when(summaryService.summary(any())).thenReturn(Mono.just(summary));
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
    }

    @Test
    void should_send_once_a_day_from_the_time_on() {
        final SummaryWatch watch = watch();

        at(21, 29);
        watch.check().as(StepVerifier::create).verifyComplete();
        verify(publisher, never()).publishInfo(anyString());

        at(21, 30);
        watch.check().as(StepVerifier::create).verifyComplete();
        at(21, 31);
        watch.check().as(StepVerifier::create).verifyComplete();
        verify(publisher).publishInfo(text);
        //built for the minute of the pass
        verify(summaryService).summary(EVENING.withMinute(30));

        nextDay();
        watch.check().as(StepVerifier::create).verifyComplete();
        verify(publisher, times(2)).publishInfo(text);
    }

    @Test
    void should_try_again_with_the_next_pass_and_give_up_after_an_hour() {
        when(publisher.publishInfo(anyString())).thenReturn(Mono.error(new IllegalStateException("broker down")));
        final SummaryWatch watch = watch();

        at(21, 30);
        watch.check().as(StepVerifier::create).verifyComplete();
        at(22, 29);
        watch.check().as(StepVerifier::create).verifyComplete();
        verify(publisher, times(2)).publishInfo(anyString());

        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
        at(22, 30);
        watch.check().as(StepVerifier::create).verifyComplete();
        at(23, 0);
        watch.check().as(StepVerifier::create).verifyComplete();
        verify(publisher, times(2)).publishInfo(anyString());
    }

    @Test
    void should_try_again_when_the_summary_cannot_be_built() {
        when(summaryService.summary(any())).thenReturn(Mono.error(new IllegalStateException("database down")))
            .thenReturn(Mono.just(summary));
        final SummaryWatch watch = watch();

        at(21, 30);
        watch.check().as(StepVerifier::create).verifyComplete();
        at(21, 31);
        watch.check().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishInfo(text);
    }

    @Test
    void should_send_nothing_the_day_it_started_after_the_time() {
        at(21, 45);
        final SummaryWatch watch = watch();

        watch.check().as(StepVerifier::create).verifyComplete();
        verify(summaryService, never()).summary(any());

        nextDay();
        watch.check().as(StepVerifier::create).verifyComplete();
        verify(publisher).publishInfo(text);
    }

    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        final SummaryWatch watch = watch();
        at(21, 30);
        doReturn(Mono.never()).when(summaryService).summary(any());

        StepVerifier.withVirtualTime(watch::check)
            .expectSubscription()
            .expectNoEvent(SummaryWatch.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
        verify(publisher, never()).publishInfo(anyString());
    }
}

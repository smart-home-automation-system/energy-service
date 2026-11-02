package cloud.cholewa.energy.production;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

//the sun at the house (HAS-333): 2 November near Krakow rises about 06:40 and sets about 16:20; the civil twilight is
//outside the day of the sun
class SunCalendarTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private final SunCalendar calendar = new SunCalendar(new LocationProperties(50.11, 19.63),
        Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), HOUSE));

    @Test
    void should_know_the_sunrise_and_the_sunset_of_the_house() {
        final SunCalendar.SunDay day = calendar.of(LocalDate.of(2026, 11, 2));

        assertThat(day.sunrise()).isBetween(LocalDateTime.of(2026, 11, 2, 6, 30), LocalDateTime.of(2026, 11, 2, 6, 50));
        assertThat(day.sunset()).isBetween(LocalDateTime.of(2026, 11, 2, 16, 10), LocalDateTime.of(2026, 11, 2, 16, 30));
    }

    @Test
    void should_have_the_sun_up_between_them_only() {
        final SunCalendar.SunDay day = calendar.of(LocalDate.of(2026, 11, 2));

        assertThat(day.up(LocalDateTime.of(2026, 11, 2, 12, 0))).isTrue();
        assertThat(day.up(LocalDateTime.of(2026, 11, 2, 6, 15))).isFalse();
        assertThat(day.up(LocalDateTime.of(2026, 11, 2, 16, 45))).isFalse();
        assertThat(new SunCalendar.SunDay(null, null).up(LocalDateTime.of(2026, 11, 2, 12, 0))).isFalse();
    }

    @Test
    void should_work_a_day_out_once() {
        assertThat(calendar.of(LocalDate.of(2026, 11, 2))).isSameAs(calendar.of(LocalDate.of(2026, 11, 2)));
    }
}

package cloud.cholewa.energy.production;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.shredzone.commons.suncalc.SunTimes;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

//The sunrise and the sunset at the house (HAS-333), worked out the first time a day is asked for and kept for a few days
//around it - the third copy of the SunCalendar of blinds-service and light-service (the commons rule: up to three stay).
//Visual rise and set: the civil dawn and dusk are outside, so a low sun is never judged. Only the times within the day
//itself count (oneDay); a day without one is never sunny
@Slf4j
@Component
@RequiredArgsConstructor
public class SunCalendar {

    private final LocationProperties location;
    private final Clock clock;
    private final Map<LocalDate, SunDay> cached = new ConcurrentHashMap<>();

    //the sunrise and the sunset of a day, house time; null for one the day does not have
    public record SunDay(LocalDateTime sunrise, LocalDateTime sunset) {

        //whether the sun is above the horizon at this time
        public boolean up(final LocalDateTime at) {
            return sunrise != null && sunset != null && !at.isBefore(sunrise) && at.isBefore(sunset);
        }
    }

    public SunDay of(final LocalDate date) {
        final SunDay held = cached.get(date);
        if (held != null) {
            return held;
        }
        final SunDay computed = compute(date);
        cached.keySet().removeIf(kept -> kept.isBefore(date.minusDays(2)) || kept.isAfter(date.plusDays(2)));
        cached.put(date, computed);
        return computed;
    }

    private SunDay compute(final LocalDate date) {
        final SunTimes times = SunTimes.compute()
            .timezone(clock.getZone())
            .on(date)
            .at(location.latitude(), location.longitude())
            .twilight(SunTimes.Twilight.VISUAL)
            .oneDay()
            .execute();
        final SunDay day = new SunDay(local(times.getRise(), date), local(times.getSet(), date));
        log.info("Sun on {} at the house: sunrise {}, sunset {}", date, day.sunrise(), day.sunset());
        return day;
    }

    //a time of another day is none of this one
    private static LocalDateTime local(final ZonedDateTime time, final LocalDate date) {
        return time == null || !time.toLocalDate().equals(date) ? null : time.toLocalDateTime();
    }
}

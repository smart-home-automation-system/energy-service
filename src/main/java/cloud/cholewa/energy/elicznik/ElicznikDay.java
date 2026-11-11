package cloud.cholewa.energy.elicznik;

import cloud.cholewa.energy.database.ElicznikHourEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

//A day of the portal made whole (HAS-340), without I/O - pinned by ElicznikDayTest: the hours taken from the grid and the
//hours given to it joined by the hour of the portal. A day is whole only when both answer every hour of it on the clock of
//the house - 24, 23 the night the clocks go forward, 25 the night they go back - and every one known; otherwise it is not
//stored and is asked again the next morning
final class ElicznikDay {

    private ElicznikDay() {
    }

    private record Key(int hour, boolean repeated) {
    }

    //the hours of the day as the house has them: 23, 24 or 25
    static int hoursOf(final LocalDate day, final ZoneId zone) {
        return Math.toIntExact(Duration.between(day.atStartOfDay(zone), day.plusDays(1).atStartOfDay(zone)).toHours());
    }

    //the rows of a whole day, by the hour (the repeated hour first); empty while the day is not whole
    static Optional<List<ElicznikHourEntity>> whole(final LocalDate day, final ZoneId zone,
                                                    final List<ElicznikAnswers.Hour> imported,
                                                    final List<ElicznikAnswers.Hour> exported,
                                                    final LocalDateTime fetchedAt) {
        final int expected = hoursOf(day, zone);
        final Map<Key, BigDecimal> taken = known(imported);
        final Map<Key, BigDecimal> given = known(exported);
        if (taken.size() != expected || given.size() != expected || !taken.keySet().equals(given.keySet())
            || imported.size() != expected || exported.size() != expected) {
            return Optional.empty();
        }
        return Optional.of(taken.keySet().stream()
            .sorted(Comparator.comparingInt(Key::hour).thenComparing(Key::repeated, Comparator.reverseOrder()))
            .map(key -> new ElicznikHourEntity(day, key.hour(), key.repeated(), taken.get(key), given.get(key), fetchedAt))
            .toList());
    }

    //the known hours, each once - a second answer of the same hour makes the day not whole
    private static Map<Key, BigDecimal> known(final List<ElicznikAnswers.Hour> hours) {
        final Map<Key, BigDecimal> known = new HashMap<>();
        for (final ElicznikAnswers.Hour hour : hours) {
            if (hour.kwh() != null) {
                known.merge(new Key(hour.hour(), hour.repeated()), hour.kwh(), (first, second) -> null);
            }
        }
        return known;
    }
}

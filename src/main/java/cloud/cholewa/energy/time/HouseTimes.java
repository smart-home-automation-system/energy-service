package cloud.cholewa.energy.time;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

//The times the notifications of the service name (HAS-334, HAS-332): the hour of the house and the length of an
//episode, "1 h 5 min" or "25 min"
public final class HouseTimes {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private HouseTimes() {
    }

    public static String hour(final LocalDateTime time) {
        return HOUR.format(time);
    }

    //the times of the house in its zone, so that an episode across the change of the clocks has its real length
    public static String length(final LocalDateTime from, final LocalDateTime to) {
        final ZoneId house = ZoneId.systemDefault();
        return minutes(Math.max(0, Duration.between(from.atZone(house), to.atZone(house)).toMinutes()));
    }

    public static String minutes(final long minutes) {
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }
}

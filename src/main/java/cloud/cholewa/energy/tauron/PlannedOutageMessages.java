package cloud.cholewa.energy.tauron;

import cloud.cholewa.energy.database.PlannedOutageEntity;
import cloud.cholewa.energy.time.HouseTimes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

//The words of the notices of the planned outages (HAS-337), English as every alert of the services. The words of Tauron
//(which streets, in Polish) close the first notice; the reminders leave them out - they go by SMS again
final class PlannedOutageMessages {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private PlannedOutageMessages() {
    }

    static String planned(final PlannedOutageEntity row) {
        return "Planned outage of the grid (Tauron): " + span(row) + ". " + row.description();
    }

    static String changed(final PlannedOutageEntity row) {
        return "Planned outage of the grid changed (Tauron): now " + span(row) + ". " + row.description();
    }

    static String moved(final PlannedOutageEntity row) {
        return "Planned outage of the grid moved (Tauron) from " + at(row.movedFrom()) + " to " + span(row) + ". "
            + row.description();
    }

    static String cancelled(final PlannedOutageEntity row) {
        return "Planned outage of the grid cancelled (Tauron): " + span(row) + " is no longer announced.";
    }

    static String failure(final PlannedOutageEntity row) {
        return "Failure of the grid at the street of the house (Tauron), power expected back at " + at(row.endsAt())
            + ". " + row.description();
    }

    static String inTheTown(final PlannedOutageEntity row) {
        final String what = Announcement.PLANNED.equals(row.kind())
            ? "Planned outage in the town (Tauron), the street of the house not named: " + span(row)
            : "Failure of the grid in the town (Tauron), the street of the house not named, power expected back at "
                + at(row.endsAt());
        return what + ". " + row.description();
    }

    static String tomorrow(final PlannedOutageEntity row) {
        return "Reminder: the grid is off tomorrow, " + span(row) + " (Tauron, planned).";
    }

    static String soon(final PlannedOutageEntity row) {
        return "Reminder: the grid goes off at " + HouseTimes.hour(row.startsAt()) + ", " + span(row)
            + " (Tauron, planned).";
    }

    //"Thu 12 Nov 08:00-13:00", "Thu 12 Nov 22:00 - Fri 13 Nov 06:00"
    static String span(final PlannedOutageEntity row) {
        return row.startsAt().toLocalDate().equals(row.endsAt().toLocalDate())
            ? at(row.startsAt()) + "-" + HouseTimes.hour(row.endsAt())
            : at(row.startsAt()) + " - " + at(row.endsAt());
    }

    private static String at(final LocalDateTime time) {
        return DAY.format(time) + " " + HouseTimes.hour(time);
    }
}

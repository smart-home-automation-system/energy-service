package cloud.cholewa.energy.tauron;

import cloud.cholewa.energy.database.PlannedOutageEntity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

//What is to be told of one row of planned_outage now (HAS-337), without I/O - at most one notice a row a pass:
//- a notice pending: new / changed / moved of a planned outage of the street as an alert by SMS, new of a failure of the
//  street as an alert (Discord), new of the town (planned or a failure) as an info, cancelled as an info; one of an
//  outage already over is dropped untold (except the cancellation, which is told whenever)
//- else the reminders of a planned outage of the street told before, both by SMS: from remindBefore ahead of its start
//  (told less than that ahead - no reminder), and from reminderAt of the day before until then (told after it - none);
//  a pass that finds both due tells the later one and marks both
record NoticeRules(LocalTime reminderAt, Duration remindBefore) {

    //NONE - nothing published, the row only stored (a notice of an outage over)
    enum Channel { ALERT_SMS, ALERT, INFO, REMINDER_SMS, NONE }

    //the text, its channel and the row as it is once the notice was published
    record Notice(String text, Channel channel, PlannedOutageEntity told) {
    }

    Optional<Notice> due(final PlannedOutageEntity row, final LocalDateTime now) {
        return Optional.ofNullable(row.notice())
            .map(notice -> pending(row, notice, now))
            .orElseGet(() -> reminder(row, now));
    }

    private Optional<Notice> pending(final PlannedOutageEntity row, final String notice, final LocalDateTime now) {
        final PlannedOutageEntity told = row.withNotice(null).withNotifiedAt(now);
        if (!AnnouncementRules.CANCELLED.equals(notice) && !row.endsAt().isAfter(now)) {
            return Optional.of(new Notice("", Channel.NONE, row.withNotice(null)));
        }
        final boolean street = AddressMatch.Scope.STREET.value().equals(row.scope());
        final boolean planned = Announcement.PLANNED.equals(row.kind());
        return Optional.of(switch (notice) {
            case AnnouncementRules.CHANGED -> new Notice(PlannedOutageMessages.changed(row), Channel.ALERT_SMS, told);
            case AnnouncementRules.MOVED -> new Notice(PlannedOutageMessages.moved(row), Channel.ALERT_SMS, told);
            case AnnouncementRules.CANCELLED -> new Notice(PlannedOutageMessages.cancelled(row), Channel.INFO, told);
            default -> street && planned
                ? new Notice(PlannedOutageMessages.planned(row), Channel.ALERT_SMS, told)
                : street
                ? new Notice(PlannedOutageMessages.failure(row), Channel.ALERT, told)
                : new Notice(PlannedOutageMessages.inTheTown(row), Channel.INFO, told);
        });
    }

    private Optional<Notice> reminder(final PlannedOutageEntity row, final LocalDateTime now) {
        if (row.closedAt() != null || row.notifiedAt() == null || !AnnouncementRules.plannedForTheStreet(row)
            || !now.isBefore(row.startsAt())) {
            return Optional.empty();
        }
        final LocalDateTime beforeStart = row.startsAt().minus(remindBefore);
        final LocalDateTime dayBefore = row.startsAt().toLocalDate().minusDays(1).atTime(reminderAt);
        if (row.beforeStartRemindedAt() == null && !now.isBefore(beforeStart) && row.notifiedAt().isBefore(beforeStart)) {
            return Optional.of(new Notice(PlannedOutageMessages.soon(row), Channel.REMINDER_SMS,
                row.withBeforeStartRemindedAt(now)
                    .withDayBeforeRemindedAt(Optional.ofNullable(row.dayBeforeRemindedAt()).orElse(now))));
        }
        if (row.dayBeforeRemindedAt() == null && !now.isBefore(dayBefore) && now.isBefore(beforeStart)
            && row.notifiedAt().isBefore(dayBefore)) {
            return Optional.of(new Notice(PlannedOutageMessages.tomorrow(row), Channel.REMINDER_SMS,
                row.withDayBeforeRemindedAt(now)));
        }
        return Optional.empty();
    }
}

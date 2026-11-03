package cloud.cholewa.energy.tauron;

import cloud.cholewa.energy.database.PlannedOutageEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

//What a read of the announcements changes in planned_outage (HAS-337), without I/O: the rows to save. An outage is its
//id and its start.
//- announced and held: seen again; its end, words and scope as announced. A new end of a planned outage of the street
//  told before is "changed" (a change of the hours, a new notice); one that came to name the street is "new" (told as
//  the street's); a notice not told yet stays
//- announced and not held: "new" - or "moved" when a planned outage of the street told before, with the same id and
//  still ahead, is no longer announced (closed as moved; the new row keeps its start in movedFrom)
//- held and no longer announced: closed only when the read before missed it too (one answer without it may be Tauron's
//  hiccup - an outage cancelled and announced again would be two more notices), at once when it is over; a planned one
//  still ahead is cancelled ("cancelled" told only for the street and once it was told); any other ended - silently
//An announcement repeated in one answer counts once. The result names the outages missed by this read, for the next
//  - a move is closed at once: the new start confirms it
final class AnnouncementRules {

    static final String NEW = "new";
    static final String CHANGED = "changed";
    static final String MOVED = "moved";
    static final String CANCELLED = "cancelled";
    static final String ENDED = "ended";

    private AnnouncementRules() {
    }

    //the rows to save and the keys of the held outages this read missed and left open
    record Result(List<PlannedOutageEntity> rows, Set<String> missed) {
    }

    static Result reconcile(final List<PlannedOutageEntity> held, final List<Announcement> announced,
                            final Set<String> missedBefore, final LocalDateTime now) {
        final Map<String, PlannedOutageEntity> byKey = new LinkedHashMap<>();
        held.forEach(row -> byKey.putIfAbsent(key(row), row));
        final Map<String, Announcement> heard = new LinkedHashMap<>();
        announced.forEach(announcement -> heard.putIfAbsent(announcement.key(), announcement));

        final List<PlannedOutageEntity> rows = new ArrayList<>();
        final Set<String> kept = new HashSet<>();
        final List<Announcement> fresh = new ArrayList<>();
        heard.values().forEach(announcement -> Optional.ofNullable(byKey.get(announcement.key()))
            .ifPresentOrElse(row -> {
                kept.add(key(row));
                rows.add(seenAgain(row, announcement, now));
            }, () -> fresh.add(announcement)));

        final List<PlannedOutageEntity> gone = byKey.values().stream()
            .filter(row -> !kept.contains(key(row)))
            .toList();
        final Set<String> moved = new HashSet<>();
        fresh.forEach(announcement -> {
            final Optional<PlannedOutageEntity> before = gone.stream()
                .filter(row -> !moved.contains(key(row)))
                .filter(row -> announcement.plannedForTheStreet() && plannedForTheStreet(row))
                .filter(row -> row.outageId().equals(announcement.outageId()))
                .filter(row -> row.notifiedAt() != null && row.startsAt().isAfter(now))
                .findFirst();
            before.ifPresent(row -> {
                moved.add(key(row));
                rows.add(row.withClosedAt(now).withClosedAs(MOVED).withNotice(null));
            });
            rows.add(newRow(announcement, now, before.map(PlannedOutageEntity::startsAt).orElse(null)));
        });
        final Set<String> missed = new HashSet<>();
        gone.stream()
            .filter(row -> !moved.contains(key(row)))
            .forEach(row -> {
                if (missedBefore.contains(key(row)) || !row.endsAt().isAfter(now)) {
                    rows.add(closed(row, now));
                } else {
                    missed.add(key(row));
                }
            });
        return new Result(rows, Set.copyOf(missed));
    }

    private static PlannedOutageEntity seenAgain(final PlannedOutageEntity row, final Announcement announcement,
                                                 final LocalDateTime now) {
        final boolean toTheStreet = AddressMatch.Scope.TOWN.value().equals(row.scope())
            && AddressMatch.Scope.STREET.value().equals(announcement.scope());
        final boolean newHours = !row.endsAt().equals(announcement.endsAt()) && announcement.plannedForTheStreet()
            && row.notifiedAt() != null;
        final String notice = Optional.ofNullable(row.notice())
            .orElseGet(() -> noticeOf(toTheStreet, newHours));
        return row.withLastSeenAt(now)
            .withEndsAt(announcement.endsAt())
            .withDescription(announcement.description())
            .withScope(announcement.scope())
            .withNotice(notice);
    }

    //an outage that came to name the street is told as new, a new end of one told before as changed
    private static String noticeOf(final boolean toTheStreet, final boolean newHours) {
        if (toTheStreet) {
            return NEW;
        }
        return newHours ? CHANGED : null;
    }

    private static PlannedOutageEntity newRow(final Announcement announcement, final LocalDateTime now,
                                              final LocalDateTime movedFrom) {
        return new PlannedOutageEntity(null, announcement.outageId(), announcement.kind(), announcement.scope(),
            announcement.startsAt(), announcement.endsAt(), announcement.description(), now, now,
            movedFrom == null ? NEW : MOVED, movedFrom, null, null, null, null, null, false, null);
    }

    private static PlannedOutageEntity closed(final PlannedOutageEntity row, final LocalDateTime now) {
        final boolean cancelled = Announcement.PLANNED.equals(row.kind()) && row.startsAt().isAfter(now);
        final boolean tell = cancelled && plannedForTheStreet(row) && row.notifiedAt() != null;
        return row.withClosedAt(now)
            .withClosedAs(cancelled ? CANCELLED : ENDED)
            .withNotice(tell ? CANCELLED : null);
    }

    static boolean plannedForTheStreet(final PlannedOutageEntity row) {
        return Announcement.PLANNED.equals(row.kind()) && AddressMatch.Scope.STREET.value().equals(row.scope());
    }

    static String key(final PlannedOutageEntity row) {
        return row.outageId() + "|" + Objects.requireNonNull(row.startsAt());
    }
}

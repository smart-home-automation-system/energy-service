package cloud.cholewa.energy.tauron;

import cloud.cholewa.energy.database.PlannedOutageEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
//Tauron sometimes announces one outage twice, under two ids (HAS-477, seen 2026-11-22): the announcements with the same
//start, end, kind and words (Twin - the words as AddressMatch reads them) are one outage.
//- one row for them: the row already held, the older of two; it keeps its id of Tauron. A twin never held is not stored
//- two open rows of one outage (stored before this rule): the older stays, the newer is closed as ended, silently, its
//  charge run and its skip carried to the row kept
//- the announcement a row is keyed by gone while its twin is still announced: the row takes the id of the twin and
//  goes on - not cancelled, not new, no notice. From then on it is found by that id, a move of the twin included
final class AnnouncementRules {

    static final String NEW = "new";
    static final String CHANGED = "changed";
    static final String MOVED = "moved";
    static final String CANCELLED = "cancelled";
    static final String ENDED = "ended";

    private static final Comparator<PlannedOutageEntity> OLDER_FIRST = Comparator
        .comparing(PlannedOutageEntity::firstSeenAt)
        .thenComparing(PlannedOutageEntity::id, Comparator.nullsLast(Comparator.naturalOrder()));

    private AnnouncementRules() {
    }

    //the rows to save and the keys of the held outages this read missed and left open
    record Result(List<PlannedOutageEntity> rows, Set<String> missed) {
    }

    //what makes two announcements one outage whatever their ids
    record Twin(LocalDateTime startsAt, LocalDateTime endsAt, String kind, String words) {

        static Twin of(final Announcement announcement) {
            return new Twin(announcement.startsAt(), announcement.endsAt(), announcement.kind(),
                AddressMatch.words(announcement.description()));
        }

        static Twin of(final PlannedOutageEntity row) {
            return new Twin(row.startsAt(), row.endsAt(), row.kind(), AddressMatch.words(row.description()));
        }
    }

    static Result reconcile(final List<PlannedOutageEntity> held, final List<Announcement> announced,
                            final Set<String> missedBefore, final LocalDateTime now) {
        final Map<String, PlannedOutageEntity> byKey = new LinkedHashMap<>();
        held.forEach(row -> byKey.putIfAbsent(key(row), row));
        final Map<String, Announcement> heard = new LinkedHashMap<>();
        announced.forEach(announcement -> heard.putIfAbsent(announcement.key(), announcement));
        final Map<Twin, List<Announcement>> outages = heard.values().stream()
            .collect(Collectors.groupingBy(Twin::of, LinkedHashMap::new, Collectors.toList()));

        final List<PlannedOutageEntity> rows = new ArrayList<>();
        final Set<String> kept = new HashSet<>();
        final List<List<Announcement>> fresh = new ArrayList<>();
        outages.values().forEach(twins -> {
            final List<PlannedOutageEntity> stored = twins.stream()
                .flatMap(announcement -> Optional.ofNullable(byKey.get(announcement.key())).stream())
                .sorted(OLDER_FIRST)
                .toList();
            stored.forEach(row -> kept.add(key(row)));
            stored.stream().findFirst().ifPresentOrElse(
                row -> rows.addAll(asOne(row, stored, heard.get(key(row)), now)),
                () -> fresh.add(twins));
        });

        final List<PlannedOutageEntity> gone = byKey.values().stream()
            .filter(row -> !kept.contains(key(row)))
            .toList();
        final Set<String> settled = new HashSet<>();
        fresh.forEach(twins -> {
            final Announcement announcement = twins.getFirst();
            final List<PlannedOutageEntity> under = gone.stream()
                .filter(row -> !settled.contains(key(row)))
                .filter(row -> Twin.of(row).equals(Twin.of(announcement)))
                .sorted(OLDER_FIRST)
                .toList();
            under.forEach(row -> settled.add(key(row)));
            under.stream().findFirst().ifPresentOrElse(
                row -> rows.addAll(asOne(row.withOutageId(announcement.outageId()), under, announcement, now)),
                () -> rows.addAll(newOrMoved(twins, gone, settled, now)));
        });
        final Set<String> missed = new HashSet<>();
        gone.stream()
            .filter(row -> !settled.contains(key(row)))
            .forEach(row -> {
                if (missedBefore.contains(key(row)) || !row.endsAt().isAfter(now)) {
                    rows.add(closed(row, now));
                } else {
                    missed.add(key(row));
                }
            });
        return new Result(rows, Set.copyOf(missed));
    }

    //the rows of one outage: the first (the oldest) seen again with what the others carry, the others closed silently
    private static List<PlannedOutageEntity> asOne(final PlannedOutageEntity keep,
                                                   final List<PlannedOutageEntity> stored,
                                                   final Announcement announcement, final LocalDateTime now) {
        final List<PlannedOutageEntity> twins = stored.stream().skip(1).toList();
        final PlannedOutageEntity carried = keep
            .withSkipAutomation(keep.skipAutomation() || twins.stream().anyMatch(PlannedOutageEntity::skipAutomation))
            .withChargeRunId(Optional.ofNullable(keep.chargeRunId())
                .or(() -> twins.stream().map(PlannedOutageEntity::chargeRunId).filter(Objects::nonNull).findFirst())
                .orElse(null));
        final List<PlannedOutageEntity> rows = new ArrayList<>();
        twins.forEach(twin -> rows.add(twin.withClosedAt(now).withClosedAs(ENDED).withNotice(null)));
        rows.add(seenAgain(carried, announcement, now));
        return rows;
    }

    //an outage not held: new, or the move of one of the street told before that is no longer announced under its id
    private static List<PlannedOutageEntity> newOrMoved(final List<Announcement> twins,
                                                        final List<PlannedOutageEntity> gone,
                                                        final Set<String> settled, final LocalDateTime now) {
        final Announcement announcement = twins.getFirst();
        final Optional<PlannedOutageEntity> before = gone.stream()
            .filter(row -> !settled.contains(key(row)))
            .filter(row -> announcement.plannedForTheStreet() && plannedForTheStreet(row))
            .filter(row -> twins.stream().anyMatch(twin -> twin.outageId().equals(row.outageId())))
            .filter(row -> row.notifiedAt() != null && row.startsAt().isAfter(now))
            .findFirst();
        final List<PlannedOutageEntity> rows = new ArrayList<>();
        before.ifPresent(row -> {
            settled.add(key(row));
            rows.add(row.withClosedAt(now).withClosedAs(MOVED).withNotice(null));
        });
        rows.add(newRow(announcement, now, before.map(PlannedOutageEntity::startsAt).orElse(null)));
        return rows;
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

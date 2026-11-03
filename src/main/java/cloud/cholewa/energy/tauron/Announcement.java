package cloud.cholewa.energy.tauron;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

//One outage Tauron announces, as the house reads it (HAS-337). The answer of /waapi/outages/address (checked 2026-11-03):
//- IdsWWW [0] - an address Tauron does not serve; OutageItems the outages, of the address (OutageListType 1) or of its
//  whole area (2, the house's case - AddressPoint null and no AddressPointIds, so AddressMatch reads the words)
//- an item: OutageId (shared by the days of one work - an outage is its id and its start), StartDate / EndDate in UTC
//  ("2026-11-12T07:00:00Z"), TypeId 1 planned / 2 a failure, IsActive, Message - the towns and streets in words
//A failure counts only while IsActive; another TypeId, an item without its id, its times or a message, or ending before
//it starts is left out. The times are the house's, cut to microseconds
public record Announcement(
    String outageId,
    String kind,
    String scope,
    LocalDateTime startsAt,
    LocalDateTime endsAt,
    String description
) {

    public static final String PLANNED = "planned";
    public static final String UNPLANNED = "unplanned";
    static final int MAX_DESCRIPTION = 1000;

    //the announcements of an answer that concern the house and end after "now", in the order of the answer
    static List<Announcement> of(final JsonNode answer, final AddressMatch match, final ZoneId house,
                                 final LocalDateTime now) {
        final List<Long> served = StreamSupport.stream(answer.path("IdsWWW").spliterator(), false)
            .map(JsonNode::asLong)
            .toList();
        if (served.equals(List.of(0L))) {
            throw new TauronException("Tauron does not serve the address of the house");
        }
        final JsonNode items = answer.path("OutageItems");
        if (!items.isArray()) {
            throw new TauronException("Tauron answered without OutageItems");
        }
        return StreamSupport.stream(items.spliterator(), false)
            .flatMap(item -> of(item, match, house).stream())
            .filter(announcement -> announcement.endsAt().isAfter(now))
            .toList();
    }

    static Optional<Announcement> of(final JsonNode item, final AddressMatch match, final ZoneId house) {
        final String id = item.path("OutageId").asString("");
        final String message = item.path("Message").asString("").strip();
        final Optional<String> kind = kindOf(item);
        final Optional<LocalDateTime> start = time(item.path("StartDate"), house);
        final Optional<LocalDateTime> end = time(item.path("EndDate"), house);
        if (id.isBlank() || message.isEmpty() || kind.isEmpty() || start.isEmpty() || end.isEmpty()
            || !end.get().isAfter(start.get())) {
            return Optional.empty();
        }
        return match.scopeOf(message)
            .map(scope -> new Announcement(id, kind.get(), scope.value(), start.get(), end.get(),
                message.length() > MAX_DESCRIPTION ? message.substring(0, MAX_DESCRIPTION) : message));
    }

    private static Optional<String> kindOf(final JsonNode item) {
        final int type = item.path("TypeId").asInt(0);
        if (type == 1) {
            return Optional.of(PLANNED);
        }
        return type == 2 && item.path("IsActive").asBoolean(false) ? Optional.of(UNPLANNED) : Optional.empty();
    }

    private static Optional<LocalDateTime> time(final JsonNode value, final ZoneId house) {
        try {
            return Optional.of(LocalDateTime.ofInstant(Instant.parse(value.asString("")), house)
                .truncatedTo(ChronoUnit.MICROS));
        } catch (final DateTimeParseException notTime) {
            return Optional.empty();
        }
    }

    String key() {
        return outageId + "|" + startsAt;
    }

    boolean plannedForTheStreet() {
        return PLANNED.equals(kind) && AddressMatch.Scope.STREET.value().equals(scope);
    }
}

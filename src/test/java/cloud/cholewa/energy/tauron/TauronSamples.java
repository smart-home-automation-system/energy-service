package cloud.cholewa.energy.tauron;

import cloud.cholewa.energy.database.PlannedOutageEntity;
import cloud.cholewa.energy.solax.SolaxSamples;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

//The samples of the planned outages (HAS-337). src/test/resources/tauron/area.json has the shape of the answer Tauron gave
//on 2026-11-03 for the address of the house (a list of the whole area, OutageListType 2, no address points) - with the
//towns, streets, ids and coordinates invented: the address of the house is in no repository
final class TauronSamples {

    static final ZoneId HOUSE = SolaxSamples.HOUSE;
    //the house time the sample answer is read at: 15:00 on Tuesday 3 November 2026
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 15, 0);
    static final AddressMatch MATCH = new AddressMatch("Testowo", "Przykładowa");
    static final String WORK = "00000000-0000-0000-0000-000000000002";

    private TauronSamples() {
    }

    static TauronProperties properties(final URI baseUrl) {
        return new TauronProperties(baseUrl, "Testowo", "Przykładowa", "1a", Duration.ofHours(1), 30,
            Duration.ofHours(24), LocalTime.of(18, 0), Duration.ofHours(2));
    }

    static String area() {
        return SolaxSamples.sample("tauron/area.json");
    }

    static JsonNode areaTree() {
        return SolaxSamples.JSON.readTree(area());
    }

    static Announcement planned(final String id, final LocalDateTime start, final LocalDateTime end) {
        return new Announcement(id, Announcement.PLANNED, "street", start, end, "Testowo ul. Przykładowa");
    }

    static PlannedOutageEntity row(final long id, final Announcement announcement) {
        return new PlannedOutageEntity(id, announcement.outageId(), announcement.kind(), announcement.scope(),
            announcement.startsAt(), announcement.endsAt(), announcement.description(), NOW.minusDays(1),
            NOW.minusHours(1), null, null, null, null, null, null, null, false);
    }

    //a planned outage of the street told the day before NOW
    static PlannedOutageEntity told(final long id, final LocalDateTime start, final LocalDateTime end) {
        return row(id, planned(WORK, start, end)).withNotifiedAt(NOW.minusDays(1));
    }
}

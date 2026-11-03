package cloud.cholewa.energy.tauron;

import cloud.cholewa.energy.solax.SolaxSamples;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//the announcements of an answer of Tauron (HAS-337): the house's by the words, the times of the house, a failure only
//while active, what is over or not a known type left out
class AnnouncementTest {

    @Test
    void should_read_the_announcements_of_the_house_from_the_answer_of_its_area() {
        final List<Announcement> announced = Announcement.of(TauronSamples.areaTree(), TauronSamples.MATCH,
            TauronSamples.HOUSE, TauronSamples.NOW);

        assertThat(announced).containsExactly(
            new Announcement(TauronSamples.WORK, "planned", "street", LocalDateTime.of(2026, 11, 12, 8, 0),
                LocalDateTime.of(2026, 11, 12, 13, 0), "Testowo ul. Lipowa, Przykladowa,  Długa"),
            new Announcement(TauronSamples.WORK, "planned", "street", LocalDateTime.of(2026, 11, 13, 8, 0),
                LocalDateTime.of(2026, 11, 13, 13, 0), "Testowo ul. Lipowa, Przykladowa,  Długa"),
            new Announcement("00000000-0000-0000-0000-000000000003", "planned", "town",
                LocalDateTime.of(2026, 11, 18, 8, 0), LocalDateTime.of(2026, 11, 18, 14, 0), "TESTOWO Ul: Sobieskiego"),
            new Announcement("00000000-0000-0000-0000-000000000005", "unplanned", "street",
                LocalDateTime.of(2026, 11, 3, 14, 27, 46, 873_000_000), LocalDateTime.of(2026, 11, 3, 18, 30),
                "Testowo ul. Przykładowa i okolice"));
    }

    @Test
    void should_refuse_an_address_tauron_does_not_serve() {
        final JsonNode notServed = SolaxSamples.JSON.readTree("{\"IdsWWW\":[0],\"OutageItems\":[]}");

        assertThatThrownBy(() -> Announcement.of(notServed, TauronSamples.MATCH, TauronSamples.HOUSE, TauronSamples.NOW))
            .isInstanceOf(TauronException.class)
            .hasMessage("Tauron does not serve the address of the house");
    }

    //{} decodes into nothing - a failed read, never "no outages"
    @Test
    void should_refuse_an_answer_without_its_outages() {
        final JsonNode empty = SolaxSamples.JSON.readTree("{}");

        assertThatThrownBy(() -> Announcement.of(empty, TauronSamples.MATCH, TauronSamples.HOUSE, TauronSamples.NOW))
            .isInstanceOf(TauronException.class)
            .hasMessage("Tauron answered without OutageItems");
    }

    @Test
    void should_leave_out_an_item_without_its_times_or_ending_before_it_starts() {
        final String answer = """
            {"IdsWWW":[999],"OutageItems":[
             {"OutageId":"a","StartDate":"not a time","EndDate":"2026-11-12T12:00:00Z","Message":"Testowo Przykładowa","TypeId":1},
             {"OutageId":"b","StartDate":"2026-11-12T12:00:00Z","EndDate":"2026-11-12T07:00:00Z","Message":"Testowo Przykładowa","TypeId":1},
             {"StartDate":"2026-11-12T07:00:00Z","EndDate":"2026-11-12T12:00:00Z","Message":"Testowo Przykładowa","TypeId":1}]}""";

        assertThat(Announcement.of(SolaxSamples.JSON.readTree(answer), TauronSamples.MATCH, TauronSamples.HOUSE,
            TauronSamples.NOW)).isEmpty();
    }
}

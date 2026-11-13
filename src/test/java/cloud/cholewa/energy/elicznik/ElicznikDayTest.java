package cloud.cholewa.energy.elicznik;

import cloud.cholewa.energy.database.ElicznikHourEntity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static cloud.cholewa.energy.elicznik.ElicznikSamples.CONSUM_2026_03_29;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.CONSUM_2026_10_25;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.CONSUM_2026_11_08;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.CONSUM_2026_11_10;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.OZE_2026_10_25;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.OZE_2026_11_08;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.read;
import static org.assertj.core.api.Assertions.assertThat;

class ElicznikDayTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final LocalDateTime FETCHED = LocalDateTime.of(2026, 11, 10, 6, 0);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private List<ElicznikAnswers.Hour> hours(final String sample) {
        return ElicznikAnswers.hours(jsonMapper, read(sample));
    }

    @Test
    void should_count_the_hours_of_a_day_on_the_clock_of_the_house() {
        assertThat(ElicznikDay.hoursOf(LocalDate.of(2026, 11, 8), HOUSE)).isEqualTo(24);
        assertThat(ElicznikDay.hoursOf(LocalDate.of(2026, 10, 25), HOUSE)).isEqualTo(25);
        assertThat(ElicznikDay.hoursOf(LocalDate.of(2026, 3, 29), HOUSE)).isEqualTo(23);
    }

    @Test
    void should_join_the_hours_taken_and_given_of_a_whole_day() {
        final Optional<List<ElicznikHourEntity>> day = ElicznikDay.whole(LocalDate.of(2026, 11, 8), HOUSE,
            hours(CONSUM_2026_11_08), hours(OZE_2026_11_08), FETCHED);

        assertThat(day).get().satisfies(rows -> {
            assertThat(rows).hasSize(24);
            assertThat(rows.get(7)).isEqualTo(new ElicznikHourEntity(LocalDate.of(2026, 11, 8), 8, false,
                new BigDecimal("0.368"), new BigDecimal("0.046"), FETCHED));
        });
    }

    //25 rows, the repeated hour 3 before the other one
    @Test
    void should_keep_the_25_hours_of_the_night_the_clocks_go_back() {
        final Optional<List<ElicznikHourEntity>> day = ElicznikDay.whole(LocalDate.of(2026, 10, 25), HOUSE,
            hours(CONSUM_2026_10_25), hours(OZE_2026_10_25), FETCHED);

        assertThat(day).get().satisfies(rows -> {
            assertThat(rows).hasSize(25);
            assertThat(rows.get(2).repeated()).isTrue();
            assertThat(rows.get(2).imported()).isEqualByComparingTo("1.392");
            assertThat(rows.get(3).repeated()).isFalse();
            assertThat(rows.get(3).imported()).isEqualByComparingTo("1.363");
        });
    }

    @Test
    void should_store_23_hours_the_night_the_clocks_go_forward() {
        final List<ElicznikAnswers.Hour> imported = hours(CONSUM_2026_03_29);
        final List<ElicznikAnswers.Hour> exported = imported.stream()
            .map(hour -> new ElicznikAnswers.Hour(hour.hour(), false, BigDecimal.ZERO))
            .toList();

        assertThat(ElicznikDay.whole(LocalDate.of(2026, 3, 29), HOUSE, imported, exported, FETCHED))
            .get().satisfies(rows -> assertThat(rows).hasSize(23));
    }

    //a day not over is not stored - asked again the next morning
    @Test
    void should_not_take_a_day_with_hours_not_known() {
        final List<ElicznikAnswers.Hour> imported = hours(CONSUM_2026_11_10);

        assertThat(ElicznikDay.whole(LocalDate.of(2026, 11, 10), HOUSE, imported, imported, FETCHED)).isEmpty();
    }

    //an answer of 24 hours the night the clocks went back is not that day whole
    @Test
    void should_not_take_a_day_of_the_wrong_length() {
        assertThat(ElicznikDay.whole(LocalDate.of(2026, 10, 25), HOUSE, hours(CONSUM_2026_11_08),
            hours(OZE_2026_11_08), FETCHED)).isEmpty();
    }

    @Test
    void should_not_take_an_hour_answered_twice() {
        final List<ElicznikAnswers.Hour> twice = new ArrayList<>(hours(CONSUM_2026_11_08));
        twice.set(1, new ElicznikAnswers.Hour(1, false, BigDecimal.ONE));

        assertThat(ElicznikDay.whole(LocalDate.of(2026, 11, 8), HOUSE, twice, hours(OZE_2026_11_08), FETCHED)).isEmpty();
    }
}

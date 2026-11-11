package cloud.cholewa.energy.elicznik;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static cloud.cholewa.energy.elicznik.ElicznikSamples.CONSUM_2026_03_29;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.CONSUM_2026_10_25;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.CONSUM_2026_11_08;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.CONSUM_2026_11_10;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.READINGS_EXPORTED;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.READINGS_IMPORTED;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElicznikAnswersTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void should_read_the_hours_of_a_day() {
        final List<ElicznikAnswers.Hour> hours = ElicznikAnswers.hours(jsonMapper, read(CONSUM_2026_11_08));

        assertThat(hours).hasSize(24);
        assertThat(hours.getFirst()).isEqualTo(new ElicznikAnswers.Hour(1, false, new BigDecimal("1.558")));
        assertThat(hours.getLast()).isEqualTo(new ElicznikAnswers.Hour(24, false, new BigDecimal("2.545")));
        assertThat(hours.stream().map(ElicznikAnswers.Hour::kwh).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("17.223");
    }

    //the night the clocks went back: two hours 3, the first one "T" - values sums them, allData keeps them apart
    @Test
    void should_keep_the_two_hours_of_the_night_the_clocks_go_back() {
        final List<ElicznikAnswers.Hour> hours = ElicznikAnswers.hours(jsonMapper, read(CONSUM_2026_10_25));

        assertThat(hours).hasSize(25);
        assertThat(hours.subList(2, 4)).containsExactly(
            new ElicznikAnswers.Hour(3, true, new BigDecimal("1.392")),
            new ElicznikAnswers.Hour(3, false, new BigDecimal("1.363")));
    }

    @Test
    void should_have_no_hour_3_the_night_the_clocks_go_forward() {
        final List<ElicznikAnswers.Hour> hours = ElicznikAnswers.hours(jsonMapper, read(CONSUM_2026_03_29));

        assertThat(hours).hasSize(23).extracting(ElicznikAnswers.Hour::hour).doesNotContain(3);
    }

    //a day not over: the hours not known yet have no energy - never 0
    @Test
    void should_leave_the_hours_not_known_without_an_energy() {
        final List<ElicznikAnswers.Hour> hours = ElicznikAnswers.hours(jsonMapper, read(CONSUM_2026_11_10));

        assertThat(hours).hasSize(24);
        assertThat(hours.get(4).kwh()).isEqualByComparingTo("1.353");
        assertThat(hours.subList(5, 24)).allSatisfy(hour -> assertThat(hour.kwh()).isNull());
    }

    @Test
    void should_read_the_registers_of_the_meter() {
        assertThat(ElicznikAnswers.registers(jsonMapper, read(READINGS_IMPORTED)))
            .hasSize(9)
            .startsWith(new ElicznikAnswers.Register(LocalDate.of(2026, 11, 1), new BigDecimal("2587")))
            .endsWith(new ElicznikAnswers.Register(LocalDate.of(2026, 11, 9), new BigDecimal("2793")));
        assertThat(ElicznikAnswers.registers(jsonMapper, read(READINGS_EXPORTED)).getLast().kwh())
            .isEqualByComparingTo("4873");
    }

    @Test
    void should_refuse_an_answer_without_success() {
        assertThatThrownBy(() -> ElicznikAnswers.hours(jsonMapper, "{\"success\":false}"))
            .isInstanceOf(ElicznikException.class)
            .hasMessage("The eLicznik portal answered without success");
    }

    //the login page answered for the data - a session that expired
    @Test
    void should_refuse_a_page_that_is_not_json() {
        assertThatThrownBy(() -> ElicznikAnswers.hours(jsonMapper, "<html>logowanie</html>"))
            .isInstanceOf(ElicznikException.class)
            .hasMessage("The eLicznik portal answered something that is not JSON");
    }

    @Test
    void should_refuse_an_energy_that_is_not_a_number() {
        final String broken = """
            {"success":true,"data":{"allData":[{"Hour":"1","EC":"x","Status":"0","Extra":"N"}]}}""";

        assertThatThrownBy(() -> ElicznikAnswers.hours(jsonMapper, broken))
            .isInstanceOf(ElicznikException.class)
            .hasMessage("The eLicznik portal answered an energy that is not one");
    }

    @Test
    void should_refuse_an_hour_out_of_the_day() {
        final String broken = """
            {"success":true,"data":{"allData":[{"Hour":"25","EC":"1","Status":"0","Extra":"N"}]}}""";

        assertThatThrownBy(() -> ElicznikAnswers.hours(jsonMapper, broken))
            .isInstanceOf(ElicznikException.class)
            .hasMessage("The eLicznik portal answered an hour that is not one");
    }

    @Test
    void should_refuse_a_reading_without_its_date() {
        final String broken = """
            {"success":true,"data":[{"Date":"yesterday","C":1}]}""";

        assertThatThrownBy(() -> ElicznikAnswers.registers(jsonMapper, broken))
            .isInstanceOf(ElicznikException.class)
            .hasMessage("The eLicznik portal answered a reading without its date");
    }
}

package cloud.cholewa.energy.forecast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static cloud.cholewa.energy.forecast.ForecastCategory.FAIR;
import static cloud.cholewa.energy.forecast.ForecastCategory.GOOD;
import static cloud.cholewa.energy.forecast.ForecastCategory.NONE;
import static cloud.cholewa.energy.forecast.ForecastCategory.POOR;
import static org.assertj.core.api.Assertions.assertThat;

//the coming days against the house load (HAS-372): the bands with their exclusive edges, the reference of the closed
//days and none without one, the day the sun comes back and the days before it
class ForecastRulesTest {

    private static final ForecastRules.Bands BANDS = new ForecastRules.Bands(0.10, 0.40, 0.80);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    private static final List<LocalDate> WEEK = TODAY.datesUntil(TODAY.plusDays(7)).toList();

    @ParameterizedTest
    @CsvSource({"0.0, NONE", "0.0999, NONE", "0.10, POOR", "0.3999, POOR", "0.40, FAIR", "0.7999, FAIR", "0.80, GOOD",
        "3.5, GOOD"})
    void should_put_a_coverage_in_its_band(final double coverage, final ForecastCategory category) {
        assertThat(ForecastRules.category(coverage, BANDS)).isEqualTo(category);
    }

    @Test
    void should_have_no_category_without_a_coverage() {
        assertThat(ForecastRules.category(null, BANDS)).isNull();
    }

    @Test
    void should_average_the_house_loads_known() {
        assertThat(ForecastRules.reference(Arrays.asList(20.0, null, 25.0, 15.0)))
            .isEqualTo(new ForecastRules.Reference(20.0, 3));
    }

    @Test
    void should_have_no_reference_without_a_house_load() {
        assertThat(ForecastRules.reference(Arrays.asList(null, null))).isNull();
        assertThat(ForecastRules.reference(List.of())).isNull();
    }

    @Test
    void should_cover_the_reference() {
        final ForecastRules.Reference reference = new ForecastRules.Reference(20.0, 7);
        assertThat(ForecastRules.coverage(5.0, reference)).isEqualTo(0.25);
        assertThat(ForecastRules.coverage(null, reference)).isNull();
        assertThat(ForecastRules.coverage(5.0, null)).isNull();
        assertThat(ForecastRules.coverage(5.0, new ForecastRules.Reference(0.0, 7))).isNull();
    }

    @Test
    void should_find_the_first_fair_or_good_day() {
        assertThat(ForecastRules.outlook(WEEK, List.of(NONE, POOR, NONE, FAIR, GOOD, POOR, NONE)))
            .isEqualTo(new ForecastRules.Outlook(TODAY.plusDays(3), 3));
    }

    @Test
    void should_have_the_sun_today() {
        assertThat(ForecastRules.outlook(WEEK, List.of(GOOD, NONE, NONE, NONE, NONE, NONE, NONE)))
            .isEqualTo(new ForecastRules.Outlook(TODAY, 0));
    }

    @Test
    void should_count_every_day_when_the_sun_does_not_come_back() {
        assertThat(ForecastRules.outlook(WEEK, List.of(NONE, POOR, NONE, POOR, NONE, NONE, POOR)))
            .isEqualTo(new ForecastRules.Outlook(null, 7));
    }

    @Test
    void should_have_no_outlook_without_a_category() {
        assertThat(ForecastRules.outlook(WEEK, Arrays.asList(null, null, null, null, null, null, null)))
            .isEqualTo(new ForecastRules.Outlook(null, null));
    }

    @Test
    void should_pass_a_day_not_known_on_the_way_to_the_sun() {
        assertThat(ForecastRules.outlook(WEEK, Arrays.asList(NONE, null, FAIR, NONE, NONE, NONE, NONE)))
            .isEqualTo(new ForecastRules.Outlook(TODAY.plusDays(2), 2));
    }
}

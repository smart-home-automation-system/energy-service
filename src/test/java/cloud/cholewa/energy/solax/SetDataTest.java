package cloud.cholewa.energy.solax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.stream.Collectors;

import static cloud.cholewa.energy.solax.SolaxSamples.JSON;
import static cloud.cholewa.energy.solax.SolaxSamples.sample;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//the map of ReadSetData (HAS-518) on the answers of the real dongle: 2026-11-25 in Self Use (the reserve switched off
//from the dashboard, Back Up kept its 80 / 80), 2026-11-26 09:06 in Feed-in Priority (switched by the owner in the SolaX
//application, Back Up kept 75 / 80), 2026-11-26 09:12 back in Back Up with its Min SoC set to 80 by hand
class SetDataTest {

    static final String SELF_USE = "solax-settings/2026-11-25-self-use.json";
    static final String FEED_IN = "solax-settings/2026-11-26-feed-in-priority.json";
    static final String BACK_UP = "solax-settings/2026-11-26-back-up.json";

    private static String with(final int index, final String value) {
        final String[] words = sample(BACK_UP).trim().replace("[", "").replace("]", "").split(",");
        words[index] = value;
        return Arrays.stream(words).collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    void should_read_back_up_with_its_min_soc_and_charge_to() {
        assertThat(SetData.parse(sample(BACK_UP), JSON)).isEqualTo(new SolaxSettings(161, WorkMode.BACK_UP, 80, 80));
    }

    @Test
    void should_read_feed_in_priority_with_the_percentages_back_up_keeps() {
        assertThat(SetData.parse(sample(FEED_IN), JSON))
            .isEqualTo(new SolaxSettings(164, WorkMode.FEED_IN_PRIORITY, 75, 80));
    }

    @Test
    void should_read_self_use() {
        assertThat(SetData.parse(sample(SELF_USE), JSON)).isEqualTo(new SolaxSettings(160, WorkMode.SELF_USE, 80, 80));
    }

    //a code never seen is a mode the service does not know - other, with its code, never one of the three
    @Test
    void should_read_a_work_mode_it_does_not_know_as_other() {
        assertThat(SetData.parse(with(71, "163"), JSON)).isEqualTo(new SolaxSettings(163, WorkMode.OTHER, 80, 80));
    }

    @Test
    void should_not_know_a_percentage_out_of_its_range() {
        assertThat(SetData.parse(with(146, "15127"), JSON).backUpMinSoc()).isNull();
        assertThat(SetData.parse(with(148, "101"), JSON).backUpChargeTo()).isNull();
    }

    //another firmware or inverter: the same indexes would be a guess
    @Test
    void should_fail_on_an_array_of_another_length() {
        final String shorter = sample(BACK_UP).trim().replaceFirst("^\\[\\d+,", "[");

        assertThatThrownBy(() -> SetData.parse(shorter, JSON))
            .isInstanceOf(SolaxCallException.class)
            .hasMessage("The dongle answered settings that are not the 493 numbers this service knows: 492 values");
    }

    @Test
    void should_fail_on_an_answer_that_is_no_array() {
        assertThatThrownBy(() -> SetData.parse("{\"Data\": []}", JSON))
            .isInstanceOf(SolaxCallException.class)
            .hasMessage("The dongle answered settings that are not the 493 numbers this service knows: no array");
    }

    @Test
    void should_fail_on_a_setting_that_is_not_a_number() {
        assertThatThrownBy(() -> SetData.parse(with(146, "\"80\""), JSON))
            .isInstanceOf(SolaxCallException.class)
            .hasMessage("The dongle answered setting [146] that is not a number: \"80\"");
    }

    @Test
    void should_fail_on_a_refused_registration_number_an_empty_body_and_no_json() {
        assertThatThrownBy(() -> SetData.parse("0:code,message:\"failed\"}", JSON))
            .isInstanceOf(RegistrationRefusedException.class);
        assertThatThrownBy(() -> SetData.parse("", JSON)).hasMessage("The dongle answered without a body");
        assertThatThrownBy(() -> SetData.parse("<html>busy</html>", JSON))
            .hasMessage("The dongle answered something that is not JSON");
    }

    //the names of the API - the contract of the dashboard
    @ParameterizedTest
    @CsvSource({"160, self use", "161, back up", "164, feed-in priority", "162, other", "0, other", "-1, other"})
    void should_name_the_work_modes(final int code, final String name) {
        assertThat(WorkMode.of(code).wireName()).isEqualTo(name);
    }
}

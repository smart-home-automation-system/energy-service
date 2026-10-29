package cloud.cholewa.energy.solax;

import org.junit.jupiter.api.Test;

import static cloud.cholewa.energy.solax.SolaxSamples.DAY;
import static cloud.cholewa.energy.solax.SolaxSamples.EARLIER_EVENING;
import static cloud.cholewa.energy.solax.SolaxSamples.EVENING;
import static cloud.cholewa.energy.solax.SolaxSamples.GRID_IMPORT;
import static cloud.cholewa.energy.solax.SolaxSamples.JSON;
import static cloud.cholewa.energy.solax.SolaxSamples.reading;
import static cloud.cholewa.energy.solax.SolaxSamples.sample;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//The map of type 33 (HAS-328), each index pinned on a recorded answer and the value SolaxCloud showed for it within
//one refresh - see SolaxSamples.EVENING
class Type33Test {

    @Test
    void should_read_the_ac_side_per_phase() {
        final SolaxReading reading = reading(EVENING);

        assertThat(reading.a()).isEqualTo(new SolaxReading.Phase(234.0, 2.1, 479.0));
        assertThat(reading.b()).isEqualTo(new SolaxReading.Phase(232.7, 3.6, 817.0));
        assertThat(reading.c()).isEqualTo(new SolaxReading.Phase(234.5, 2.1, 460.0));
        assertThat(reading.frequency()).isEqualTo(50.01);
        assertThat(reading.powerFactor()).isEqualTo(0.95);
        assertThat(reading.acPower()).isEqualTo(1755.0);
    }

    @Test
    void should_read_the_inverter_itself() {
        final SolaxReading reading = reading(EVENING);

        assertThat(reading.inverterSerial()).isEqualTo(SolaxSamples.SERIAL);
        assertThat(reading.runModeCode()).isEqualTo(2);
        assertThat(reading.runMode()).isEqualTo(RunMode.NORMAL);
        assertThat(reading.temperature()).isEqualTo(48.0);
        assertThat(reading.dailyYield()).isEqualTo(38.6);
        assertThat(reading.totalYield()).isEqualTo(4510.6);
    }

    //the power and the current are signed 16-bit words: 63556 is -1980 W, 65150 is -38.6 A
    @Test
    void should_read_the_battery_discharging() {
        assertThat(reading(EVENING).battery()).isEqualTo(new SolaxReading.Battery(
            64.0, 99.0, -1980.0, 52.3, -38.6, 20.0, 132, 26.6, 10.2));
    }

    @Test
    void should_read_the_battery_of_another_evening() {
        final SolaxReading.Battery battery = reading(EARLIER_EVENING).battery();

        assertThat(battery.soc()).isEqualTo(56.0);
        assertThat(battery.power()).isEqualTo(-1695.0);
        assertThat(battery.current()).isEqualTo(-33.2);
        assertThat(battery.voltage()).isEqualTo(52.1);
        assertThat(battery.temperature()).isEqualTo(22.0);
    }

    //by day both strings of panels give: voltage and current ÷10, power W
    @Test
    void should_read_the_panels_by_day() {
        final SolaxReading reading = reading(DAY);

        assertThat(reading.pv1()).isEqualTo(new SolaxReading.Pv(642.1, 1.4, 897.0));
        assertThat(reading.pv2()).isEqualTo(new SolaxReading.Pv(272.8, 1.4, 385.0));
        assertThat(reading.pvPower()).isEqualTo(1282.0);
        assertThat(reading.battery().soc()).isEqualTo(11.0);
        assertThat(reading.battery().power()).isEqualTo(-1922.0);
    }

    //at night the panels give nothing - 0, not left out
    @Test
    void should_read_no_power_of_the_panels_at_night() {
        final SolaxReading reading = reading(EVENING);

        assertThat(reading.pv1()).isEqualTo(new SolaxReading.Pv(0.0, 0.0, 0.0));
        assertThat(reading.pvPower()).isZero();
    }

    //the grid power of the meter is a signed 32-bit number, the low word first and negative while the house takes:
    //63732, 65535 is -1804 W on the wire, served as 1804 W taken; a few watts given back by day are negative
    @Test
    void should_read_the_grid_power_of_the_meter_of_the_inverter() {
        assertThat(reading(GRID_IMPORT).gridPower()).isEqualTo(1804.0);
        assertThat(reading(DAY).gridPower()).isEqualTo(-11.0);
        assertThat(reading(EVENING).gridPower()).isZero();
    }

    //the total yield is two words, the low one first: past 6553.5 kWh the high word counts
    @Test
    void should_read_the_total_yield_over_two_words() {
        final String body = sample(EVENING).replace("45106,0,386", "1000,1,386");

        assertThat(Type33.parse(body, JSON).totalYield()).isEqualTo(6653.6);
    }

    //a run mode the map does not know is kept as its code and never served in words
    @Test
    void should_keep_the_code_of_a_run_mode_not_known() {
        final String body = sample(EVENING).replace("1755,0,0,0,0,0,0,2,", "1755,0,0,0,0,0,0,42,");

        final SolaxReading reading = Type33.parse(body, JSON);

        assertThat(reading.runModeCode()).isEqualTo(42);
        assertThat(reading.runMode()).isNull();
    }

    //what the dongle answers to a wrong registration number - not JSON at all
    @Test
    void should_refuse_the_answer_to_a_wrong_registration_number() {
        assertThatThrownBy(() -> Type33.parse("0:code,message:\"failed\"}", JSON))
            .isInstanceOf(RegistrationRefusedException.class)
            .hasMessageNotContaining(SolaxSamples.REGISTRATION);
    }

    @Test
    void should_refuse_an_empty_answer() {
        assertThatThrownBy(() -> Type33.parse("", JSON))
            .isInstanceOf(SolaxCallException.class)
            .hasMessage("The dongle answered without a body");
    }

    @Test
    void should_refuse_an_answer_that_is_not_json() {
        assertThatThrownBy(() -> Type33.parse("<html>busy</html>", JSON))
            .isInstanceOf(SolaxCallException.class)
            .hasMessage("The dongle answered something that is not JSON");
    }

    //another type has another layout: none of its values is read
    @Test
    void should_refuse_another_type() {
        final String body = sample(EVENING).replace("\"type\":33", "\"type\":14");

        assertThatThrownBy(() -> Type33.parse(body, JSON))
            .isInstanceOf(SolaxCallException.class)
            .hasMessage("The dongle answered a type other than 33: 14");
    }

    @Test
    void should_refuse_a_data_too_short_for_the_map() {
        assertThatThrownBy(() -> Type33.parse("{\"type\":33,\"Data\":[2340,2327]}", JSON))
            .isInstanceOf(SolaxCallException.class)
            .hasMessage("The dongle answered without the Data of type 33");
    }

    //a word that is not a number fails the read - never a 0 served as fresh
    @Test
    void should_refuse_a_word_that_is_not_a_number() {
        final String body = sample(EVENING).replace("523,65150,63556", "523,null,63556");

        assertThatThrownBy(() -> Type33.parse(body, JSON))
            .isInstanceOf(SolaxCallException.class)
            .hasMessage("The dongle answered Data[37] that is not a number: null");
    }

    @Test
    void should_refuse_an_answer_without_the_serial_of_the_inverter() {
        final String body = sample(EVENING).replace("\"" + SolaxSamples.SERIAL + "\"", "null");

        assertThatThrownBy(() -> Type33.parse(body, JSON))
            .isInstanceOf(SolaxCallException.class)
            .hasMessage("The dongle answered without the serial number of the inverter");
    }

    @Test
    void should_refuse_an_answer_without_data() {
        assertThatThrownBy(() -> Type33.parse("{\"type\":33}", JSON))
            .isInstanceOf(SolaxCallException.class)
            .hasMessage("The dongle answered without the Data of type 33");
    }
}

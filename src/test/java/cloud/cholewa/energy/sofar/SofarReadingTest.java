package cloud.cholewa.energy.sofar;

import cloud.cholewa.energy.solax.SolaxSamples;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//the map of the currentData answer of the SOFAR (HAS-330), pinned on the answer of 2026-11-02 10:19 - compared with
//Solarman Smart the same minute: PV 200 W, PV1 356 V / 0.77 A / 270 W, AC 270 W, grid connected, 0.26 kWh today
class SofarReadingTest {

    @Test
    void should_read_the_recorded_answer() {
        final SofarReading reading = SofarSamples.reading();

        assertThat(reading.serialNumber()).isEqualTo(SofarSamples.SERIAL);
        assertThat(reading.collectedAt()).isEqualTo(SofarSamples.COLLECTED_AT);
        assertThat(reading.pvPower()).isEqualTo(200.0);
        assertThat(reading.pv1()).isEqualTo(new SofarReading.Pv(356.0, 0.77, 270.0));
        assertThat(reading.pv2()).isEqualTo(new SofarReading.Pv(0.0, 0.0, 0.0));
        assertThat(reading.acPower()).isEqualTo(270.0);
        assertThat(reading.a()).isEqualTo(new SofarReading.Phase(234.4, 0.78, 80.0));
        assertThat(reading.b()).isEqualTo(new SofarReading.Phase(234.1, 0.80, 90.0));
        assertThat(reading.c()).isEqualTo(new SofarReading.Phase(235.4, 0.79, 90.0));
        assertThat(reading.frequency()).isEqualTo(49.99);
        assertThat(reading.state()).isEqualTo("Grid connected");
        assertThat(reading.faults()).isEmpty();
        assertThat(reading.temperature()).isEqualTo(32.0);
        assertThat(reading.dailyYield()).isEqualTo(0.26);
        assertThat(reading.totalYield()).isEqualTo(5421.5);
    }

    //the faults are words in Sta_1 - Sta_9, empty when none; the same word twice is one fault
    @Test
    void should_read_the_faults_in_words() {
        final JsonNode answer = SofarSamples.answer();
        final Map<String, String> faults = Map.of("Sta_1", "GridOVP", "Sta_4", " NoGrid ", "Sta_9", "GridOVP");
        answer.path("dataList").valueStream()
            .filter(entry -> faults.containsKey(entry.path("key").asString()))
            .forEach(entry -> ((ObjectNode) entry).put("value", faults.get(entry.path("key").asString())));

        assertThat(SofarReading.of(answer, SofarSamples.SERIAL, SolaxSamples.HOUSE).faults())
            .isEqualTo(List.of("GridOVP", "NoGrid"));
    }

    //a value missing or not a number is not known, never 0
    @Test
    void should_leave_out_a_value_missing_or_not_a_number() {
        final JsonNode answer = SolaxSamples.JSON.readTree("""
            {"deviceSn":"SH1234567890ABCDEFGH","collectionTime":1793611158,"dataList":[
              {"key":"PVTP","value":"n/a"},{"key":"DP1","value":""},{"key":"T_AC_OP","value":"270.00"}]}""");

        final SofarReading reading = SofarReading.of(answer, SofarSamples.SERIAL, SolaxSamples.HOUSE);

        assertThat(reading.pvPower()).isNull();
        assertThat(reading.pv1().power()).isNull();
        assertThat(reading.acPower()).isEqualTo(270.0);
        assertThat(reading.state()).isNull();
        assertThat(reading.faults()).isEmpty();
    }

    @Test
    void should_refuse_an_answer_of_another_device() {
        final JsonNode answer = SofarSamples.answer();

        assertThatThrownBy(() -> SofarReading.of(answer, "SH0000000000000000000", SolaxSamples.HOUSE))
            .isInstanceOf(SolarmanException.class)
            .hasMessage("The Solarman cloud answered for another device");
    }

    @Test
    void should_refuse_an_answer_without_its_time_or_data() {
        final JsonNode noTime = SolaxSamples.JSON.readTree("""
            {"deviceSn":"SH1234567890ABCDEFGH","dataList":[]}""");
        final JsonNode noData = SolaxSamples.JSON.readTree("""
            {"deviceSn":"SH1234567890ABCDEFGH","collectionTime":1793611158}""");

        assertThatThrownBy(() -> SofarReading.of(noTime, SofarSamples.SERIAL, SolaxSamples.HOUSE))
            .hasMessage("The Solarman cloud answered no data of the SOFAR");
        assertThatThrownBy(() -> SofarReading.of(noData, SofarSamples.SERIAL, SolaxSamples.HOUSE))
            .hasMessage("The Solarman cloud answered no data of the SOFAR");
    }
}

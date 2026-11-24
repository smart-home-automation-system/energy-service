package cloud.cholewa.energy.outage;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//the sys component of the grid meter (HAS-505): the fields of the answer of the real Pro 3EM of 2026-11-24, firmware 2.2.1
class MeterSysTest {

    @Test
    void should_read_the_uptime_and_the_reset_reason_of_the_real_meter() {
        final Map<String, Object> status = Map.of(
            "em:0", Map.of("total_act_power", 840.0),
            "sys", Map.of("mac", "841FE8000000", "time", "20:51", "unixtime", 1795549870, "uptime", 49940,
                "ram_free", 68396, "reset_reason", 1, "utc_offset", 3600));

        assertThat(MeterSys.of(status)).contains(new MeterSys(49940, MeterSys.POWER_ON));
    }

    @Test
    void should_read_a_meter_that_gives_no_reset_reason() {
        assertThat(MeterSys.of(Map.of("sys", Map.of("uptime", 12)))).contains(new MeterSys(12, null));
    }

    //an answer that is not the model is a failed read, never an uptime of 0
    @Test
    void should_refuse_an_answer_without_an_uptime_that_is_a_number_of_0_or_more() {
        assertThat(MeterSys.of(Map.of())).isEmpty();
        assertThat(MeterSys.of(null)).isEmpty();
        assertThat(MeterSys.of(Map.of("sys", "up"))).isEmpty();
        assertThat(MeterSys.of(Map.of("sys", Map.of("reset_reason", 1)))).isEmpty();
        assertThat(MeterSys.of(Map.of("sys", Map.of("uptime", "49940")))).isEmpty();
        assertThat(MeterSys.of(Map.of("sys", Map.of("uptime", -1)))).isEmpty();
    }
}

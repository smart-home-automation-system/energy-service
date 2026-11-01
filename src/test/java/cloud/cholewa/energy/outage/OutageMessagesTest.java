package cloud.cholewa.energy.outage;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static cloud.cholewa.energy.outage.OutageSamples.CAPACITY;
import static cloud.cholewa.energy.outage.OutageSamples.eps;
import static cloud.cholewa.energy.outage.OutageSamples.present;
import static org.assertj.core.api.Assertions.assertThat;

//the words of the notifications of an outage (HAS-334)
class OutageMessagesTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 31, 19, 42);

    //one GSM SMS with the level notification-service puts in front ("ERROR: "), on the longest numbers there can be
    @Test
    void should_fit_one_sms_with_the_level_in_front() {
        final LocalDateTime later = START.plusDays(1).plusHours(23).plusMinutes(59);
        final List<String> texts = List.of(
            OutageMessages.lost(START, eps(100, -10), CAPACITY),
            OutageMessages.stillLost(START, later, eps(100, -10), CAPACITY),
            OutageMessages.lowBattery(START, eps(19, -10), CAPACITY),
            OutageMessages.back(START, later, present(100, 0)));

        assertThat(texts).hasSize(4).allSatisfy(text -> assertThat("ERROR: " + text).hasSizeLessThanOrEqualTo(160)
            .matches("[ -~]+"));
    }

    @Test
    void should_tell_the_time_left_only_while_the_battery_discharges() {
        assertThat(OutageMessages.battery(eps(64, -2100), CAPACITY))
            .isEqualTo("64 %, about 6 h 49 min left at 2.1 kW");
        assertThat(OutageMessages.battery(eps(64, -19), CAPACITY)).isEqualTo("64 %");
        assertThat(OutageMessages.battery(eps(64, 1500), CAPACITY)).isEqualTo("64 %");
        //at the EPS floor there is nothing left
        assertThat(OutageMessages.battery(eps(10, -2000), CAPACITY)).isEqualTo("10 %, about 0 min left at 2.0 kW");
        assertThat(OutageMessages.battery(OutageSamples.reading(7, 0.0, 0.0, 0.0, null, -2000.0), CAPACITY))
            .isEqualTo("not known");
    }

    @Test
    void should_tell_the_length_of_an_outage() {
        assertThat(OutageMessages.length(START, START.plusMinutes(25))).isEqualTo("25 min");
        assertThat(OutageMessages.length(START, START.plusMinutes(65))).isEqualTo("1 h 5 min");
        assertThat(OutageMessages.length(START, START.minusMinutes(1))).isEqualTo("0 min");
    }
}

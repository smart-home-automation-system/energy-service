package cloud.cholewa.energy.solaxcloud;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

//the day as the SolaX cloud counts it (HAS-331): by the date of the plant, the later answer of a day replacing the
//earlier, the last days only
class CloudDaysTest {

    private final CloudDays days = new CloudDays();

    @Test
    void should_hold_the_last_answers_of_each_day() {
        days.inverter(CloudSamples.inverterAt("2026-11-01 23:55:00"));
        days.plant(CloudSamples.plant());
        days.inverter(CloudSamples.inverterAt("2026-11-02 00:05:00"));

        assertThat(days.of(LocalDate.of(2026, 11, 1))).contains(new CloudDays.CloudDay(4.0, 11.2, 0.0));
        assertThat(days.of(LocalDate.of(2026, 11, 2))).contains(new CloudDays.CloudDay(4.0, null, null));
    }

    @Test
    void should_forget_the_days_before_the_last_three() {
        days.inverter(CloudSamples.inverterAt("2026-10-29 12:00:00"));
        days.inverter(CloudSamples.inverterAt("2026-10-30 12:00:00"));
        days.inverter(CloudSamples.inverterAt("2026-11-01 12:00:00"));

        assertThat(days.of(LocalDate.of(2026, 10, 29))).isEmpty();
        assertThat(days.of(LocalDate.of(2026, 10, 30))).isPresent();
    }

    @Test
    void should_ignore_an_answer_without_its_time() {
        days.inverter(CloudSamples.inverterAt("not a time"));

        assertThat(days.of(LocalDate.of(2026, 11, 1))).isEmpty();
    }
}

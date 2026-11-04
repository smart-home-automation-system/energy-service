package cloud.cholewa.energy.forecast;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

//the kWh of the forecast (HAS-372): the peak power x the irradiance / 1000 x the ratio, an hour and a day
class ForecastModelTest {

    @Test
    void should_give_the_energy_of_an_hour() {
        //8.24 kWp in 500 W/m2 for an hour at 0.8: 3.296 kWh
        assertThat(ForecastModel.kwh(8.24, 500, 0.8)).isCloseTo(3.296, within(1e-9));
    }

    @Test
    void should_sum_the_hours_of_a_day() {
        //a day of November on the SOFAR: 5 kWp, 1080 Wh/m2 in the day, at 0.8 - 4.32 kWh
        final List<Double> day = Arrays.asList(0.0, 0.0, 30.0, 120.0, 200.0, 250.0, 220.0, 160.0, 80.0, 20.0, 0.0);
        assertThat(ForecastModel.kwh(5, day, 0.8)).isCloseTo(4.32, within(1e-9));
    }

    @Test
    void should_leave_out_an_hour_not_known() {
        assertThat(ForecastModel.kwh(5, Arrays.asList(100.0, null, 300.0), 1.0)).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void should_not_know_a_day_without_an_hour() {
        assertThat(ForecastModel.kwh(5, Arrays.asList(null, null), 1.0)).isNull();
        assertThat(ForecastModel.kwh(5, List.of(), 1.0)).isNull();
    }
}

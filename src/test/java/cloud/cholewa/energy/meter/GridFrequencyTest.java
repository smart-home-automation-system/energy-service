package cloud.cholewa.energy.meter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

//the frequency of the grid meter is its phase furthest from 50 Hz, below as above (HAS-503, HAS-506)
class GridFrequencyTest {

    @Test
    void should_take_the_phase_furthest_from_50_hz() {
        assertThat(GridFrequency.furthestFromNominal(50.01, 49.7, 50.2)).isEqualTo(49.7);
        assertThat(GridFrequency.furthestFromNominal(50.0, null, 51.02)).isEqualTo(51.02);
    }

    @Test
    void should_know_no_frequency_without_a_phase() {
        assertThat(GridFrequency.furthestFromNominal(null, null, null)).isNull();
    }
}

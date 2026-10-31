package cloud.cholewa.energy.outage;

import cloud.cholewa.energy.solax.SolaxSamples;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

//only the SOLAX knows the grid (HAS-334): its run mode and the voltages at the grid connection point
class GridSignalTest {

    //the four answers recorded in the house, all with the grid there - the last one in run mode 21 with the battery
    //empty: present in any run mode but EPS, or the grid would never be back with an empty battery
    @ParameterizedTest
    @ValueSource(strings = {SolaxSamples.EVENING, SolaxSamples.DAY, SolaxSamples.GRID_IMPORT,
        SolaxSamples.EARLIER_EVENING})
    void should_see_the_grid_in_every_recorded_answer(final String sample) {
        assertThat(GridSignal.of(SolaxSamples.reading(sample))).isEqualTo(GridSignal.PRESENT);
    }

    @ParameterizedTest
    @CsvSource(value = {
        //EPS check and EPS are the grid lost, whatever the voltages say
        "6, 230, 230, 230, LOST",
        "7, 230, 230, 230, LOST",
        "7, 0, 0, 0, LOST",
        //no voltage on any phase at the grid connection point, in any other mode
        "2, 0, 0, 0, LOST",
        "3, 99.9, 12, 0, LOST",
        //all three at or above 100 V
        "2, 100, 100, 100, PRESENT",
        "21, 233.4, 232.6, 234.1, PRESENT",
        "0, 230, 230, 230, PRESENT",
        //one or two phases down: neither, the state holds
        "2, 230, 0, 230, NOT_KNOWN",
        "2, 0, 0, 230, NOT_KNOWN",
        //a voltage not known
        "2, NULL, 230, 230, NOT_KNOWN"
    }, nullValues = "NULL")
    void should_read_the_grid_from_the_run_mode_and_the_voltages(
        final int runMode,
        final Double a,
        final Double b,
        final Double c,
        final GridSignal expected
    ) {
        assertThat(GridSignal.of(OutageSamples.reading(runMode, a, b, c, 50.0, -1000.0))).isEqualTo(expected);
    }
}

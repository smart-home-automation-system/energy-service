package cloud.cholewa.energy.history;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//the names of the metrics are wire contract of the dashboard, and the sign convention of every power is pinned here
//(HAS-331): the grid positive while the house takes from it, the battery positive while it charges, the house load the
//production plus the grid minus the battery
class MetricTest {

    @Test
    void should_keep_the_names_of_the_metrics() {
        assertThat(Metric.apiNames()).containsExactly("grid-power", "grid-import-energy", "grid-export-energy",
            "pv-solax", "pv-sofar", "battery-power", "battery-soc", "pv-power", "house-load");
    }

    @Test
    void should_read_each_stored_metric_from_its_column() {
        assertThat(Metric.GRID_POWER.parts()).containsExactly(new Metric.Part(1, StoredValue.GRID_POWER));
        assertThat(Metric.BATTERY_POWER.parts()).containsExactly(new Metric.Part(1, StoredValue.BATTERY_POWER));
        assertThat(StoredValue.GRID_POWER.table()).isEqualTo("grid_reading");
        assertThat(StoredValue.GRID_POWER.column()).isEqualTo("total_power");
        assertThat(StoredValue.BATTERY_POWER.column()).isEqualTo("power");
        assertThat(StoredValue.PV_SOFAR.inverter()).isNotNull();
        assertThat(Metric.GRID_POWER.isSum()).isFalse();
    }

    //grid_reading.total_power is negative while the house returns energy (V2), battery_reading.power negative while the
    //battery discharges (V3): the house load adds the grid and takes the charging away
    @Test
    void should_sum_the_house_load_with_the_signs_of_the_house() {
        assertThat(Metric.HOUSE_LOAD.parts()).containsExactly(
            new Metric.Part(1, StoredValue.PV_SOLAX),
            new Metric.Part(1, StoredValue.PV_SOFAR),
            new Metric.Part(1, StoredValue.GRID_POWER),
            new Metric.Part(-1, StoredValue.BATTERY_POWER));
        assertThat(Metric.PV_POWER.parts()).isEqualTo(List.of(new Metric.Part(1, StoredValue.PV_SOLAX),
            new Metric.Part(1, StoredValue.PV_SOFAR)));
        assertThat(Metric.HOUSE_LOAD.isSum()).isTrue();
    }

    @Test
    void should_know_a_metric_by_its_name_only() {
        assertThat(Metric.ofApiName("house-load")).contains(Metric.HOUSE_LOAD);
        assertThat(Metric.ofApiName("HOUSE_LOAD")).isEmpty();
    }
}

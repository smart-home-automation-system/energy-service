package cloud.cholewa.energy.history;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

//The metrics of GET /home/energy/history (HAS-331) - their names are wire contract of the dashboard, pinned by
//MetricTest. A metric is a stored value, or the sum of stored values, each with its sign. The sign convention of the
//house, pinned by MetricTest and written in the README: every power is W; grid-power is positive while the house takes
//from the grid, negative while it returns (the grid meter); battery-power is positive while the battery charges, negative
//while it discharges (the SOLAX); the PV powers are never negative. house-load = pv-solax + pv-sofar + grid-power -
//battery-power: what the house uses, wherever it comes from. The voltages (V) and the currents (A, never signed) of each
//phase and the frequency (Hz, the phase furthest from 50 Hz) of the grid meter since HAS-506 - the currents and the
//frequency stored from V18 on, so their history starts with that deploy
public enum Metric {

    GRID_POWER("grid-power", StoredValue.GRID_POWER),
    //the counters of the grid meter, kWh since it was set up: max - min of a bucket is the energy of the bucket
    GRID_IMPORT_ENERGY("grid-import-energy", StoredValue.GRID_IMPORT),
    GRID_EXPORT_ENERGY("grid-export-energy", StoredValue.GRID_EXPORT),
    PV_SOLAX("pv-solax", StoredValue.PV_SOLAX),
    PV_SOFAR("pv-sofar", StoredValue.PV_SOFAR),
    BATTERY_POWER("battery-power", StoredValue.BATTERY_POWER),
    BATTERY_SOC("battery-soc", StoredValue.BATTERY_SOC),
    PV_POWER("pv-power", List.of(new Part(1, StoredValue.PV_SOLAX), new Part(1, StoredValue.PV_SOFAR))),
    HOUSE_LOAD("house-load", List.of(new Part(1, StoredValue.PV_SOLAX), new Part(1, StoredValue.PV_SOFAR),
        new Part(1, StoredValue.GRID_POWER), new Part(-1, StoredValue.BATTERY_POWER))),
    GRID_VOLTAGE_A("grid-voltage-a", StoredValue.GRID_VOLTAGE_A),
    GRID_VOLTAGE_B("grid-voltage-b", StoredValue.GRID_VOLTAGE_B),
    GRID_VOLTAGE_C("grid-voltage-c", StoredValue.GRID_VOLTAGE_C),
    GRID_CURRENT_A("grid-current-a", StoredValue.GRID_CURRENT_A),
    GRID_CURRENT_B("grid-current-b", StoredValue.GRID_CURRENT_B),
    GRID_CURRENT_C("grid-current-c", StoredValue.GRID_CURRENT_C),
    GRID_FREQUENCY("grid-frequency", StoredValue.GRID_FREQUENCY);

    private final String apiName;
    private final List<Part> parts;

    Metric(final String apiName, final StoredValue stored) {
        this(apiName, List.of(new Part(1, stored)));
    }

    Metric(final String apiName, final List<Part> parts) {
        this.apiName = apiName;
        this.parts = parts;
    }

    //a part of a metric: a stored value with its sign, 1 or -1
    record Part(int sign, StoredValue value) {
    }

    public String apiName() {
        return apiName;
    }

    //the stored values of the metric with their signs; one with the sign 1 for a metric that is no sum
    List<Part> parts() {
        return parts;
    }

    boolean isSum() {
        return parts.size() > 1;
    }

    static Optional<Metric> ofApiName(final String name) {
        return Arrays.stream(values()).filter(metric -> metric.apiName.equals(name)).findFirst();
    }

    static List<String> apiNames() {
        return Arrays.stream(values()).map(Metric::apiName).toList();
    }
}

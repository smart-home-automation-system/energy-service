package cloud.cholewa.energy.history;

import cloud.cholewa.home.model.InverterBrand;

//A value the service stores, as a column of one of its reading tables - of one inverter for inverter_reading (HAS-331).
//What the history (Metric) and the closing of a day (DayCloser) read; the SQL is built from these constants only, never
//from a request
public enum StoredValue {

    GRID_POWER("grid_reading", "total_power", null),
    GRID_IMPORT("grid_reading", "import_energy", null),
    GRID_EXPORT("grid_reading", "export_energy", null),
    PV_SOLAX("inverter_reading", "pv_power", InverterBrand.SOLAX),
    PV_SOFAR("inverter_reading", "pv_power", InverterBrand.SOFAR),
    YIELD_SOLAX("inverter_reading", "daily_yield", InverterBrand.SOLAX),
    YIELD_SOFAR("inverter_reading", "daily_yield", InverterBrand.SOFAR),
    BATTERY_POWER("battery_reading", "power", null),
    BATTERY_SOC("battery_reading", "soc", null),
    BATTERY_CHARGED_TODAY("battery_reading", "charged_today", null),
    BATTERY_DISCHARGED_TODAY("battery_reading", "discharged_today", null);

    private final String table;
    private final String column;
    private final InverterBrand inverter;

    StoredValue(final String table, final String column, final InverterBrand inverter) {
        this.table = table;
        this.column = column;
        this.inverter = inverter;
    }

    String table() {
        return table;
    }

    String column() {
        return column;
    }

    //the inverter of a value of inverter_reading, null for the others
    public InverterBrand inverter() {
        return inverter;
    }
}

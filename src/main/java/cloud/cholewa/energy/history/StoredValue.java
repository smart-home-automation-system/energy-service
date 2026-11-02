package cloud.cholewa.energy.history;

import cloud.cholewa.home.model.InverterBrand;

//A value the service stores, as a column of one of its reading tables - of one inverter for inverter_reading (HAS-331).
//What the history (Metric) and the closing of a day (DayCloser) read; the SQL is built from these constants only, never
//from a request
public enum StoredValue {

    GRID_POWER(Tables.GRID, "total_power", null),
    GRID_IMPORT(Tables.GRID, "import_energy", null),
    GRID_EXPORT(Tables.GRID, "export_energy", null),
    PV_SOLAX(Tables.INVERTER, "pv_power", InverterBrand.SOLAX),
    PV_SOFAR(Tables.INVERTER, "pv_power", InverterBrand.SOFAR),
    YIELD_SOLAX(Tables.INVERTER, "daily_yield", InverterBrand.SOLAX),
    YIELD_SOFAR(Tables.INVERTER, "daily_yield", InverterBrand.SOFAR),
    BATTERY_POWER(Tables.BATTERY, "power", null),
    BATTERY_SOC(Tables.BATTERY, "soc", null),
    BATTERY_CHARGED_TODAY(Tables.BATTERY, "charged_today", null),
    BATTERY_DISCHARGED_TODAY(Tables.BATTERY, "discharged_today", null);

    //the reading tables - a holder, as an enum constant cannot name a static field of its own enum
    private static final class Tables {
        private static final String GRID = "grid_reading";
        private static final String INVERTER = "inverter_reading";
        private static final String BATTERY = "battery_reading";
    }

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

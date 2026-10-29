package cloud.cholewa.energy.solax;

//One answer of the dongle of the SOLAX as the service understands it (HAS-328) - only the values of the map of
//type 33 (Type33); a value the map does not establish is null and never served. Units: V, A, W (signed), Hz, °C, %,
//kWh. inverterSerial - the serial number of the inverter the dongle answers for (Information[2]). gridPower - what the
//meter of the inverter measures at the grid, W, positive while the house takes from the grid (as GET /grid answers)
public record SolaxReading(
    String inverterSerial,
    int runModeCode,
    Phase a,
    Phase b,
    Phase c,
    Double frequency,
    Double powerFactor,
    Double acPower,
    Double temperature,
    Double dailyYield,
    Double totalYield,
    Pv pv1,
    Pv pv2,
    Double gridPower,
    Battery battery
) {

    //one phase of the AC side: voltage, current and power
    public record Phase(Double voltage, Double current, Double power) {
    }

    //one string of panels (an MPPT input): voltage, current and the power it gives
    public record Pv(Double voltage, Double current, Double power) {
    }

    //the battery: the state of charge and health in %, power W (negative while discharging), voltage, current
    //(negative while discharging), temperature, the cycles and the energy charged and discharged today
    public record Battery(
        Double soc,
        Double soh,
        Double power,
        Double voltage,
        Double current,
        Double temperature,
        Integer cycles,
        Double chargedToday,
        Double dischargedToday
    ) {
    }

    public RunMode runMode() {
        return RunMode.of(runModeCode).orElse(null);
    }

    //the power of both strings together, W
    public Double pvPower() {
        return pv1.power() + pv2.power();
    }
}

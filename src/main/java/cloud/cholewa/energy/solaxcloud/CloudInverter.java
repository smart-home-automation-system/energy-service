package cloud.cholewa.energy.solaxcloud;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

//The inverter as v2/device/realtime_data answers it with deviceType=1 (HAS-329) - only what the service reads.
//plantLocalTime is the time of the plant ("yyyy-MM-dd HH:mm:ss", the house time); per phase V, A, W; MPPTTotalInputPower
//W of the panels, the strings in mpptMap (MPPT1Voltage, MPPT1Current, MPPT1Power, ...). deviceStatus came back null
//(2026-10-23, 2026-11-01) and gridPower has no established sign - neither is read
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudInverter(
    String deviceSn,
    String plantLocalTime,
    Double acVoltage1,
    Double acVoltage2,
    Double acVoltage3,
    Double acCurrent1,
    Double acCurrent2,
    Double acCurrent3,
    Double acPower1,
    Double acPower2,
    Double acPower3,
    Double acFrequency1,
    Double totalPowerFactor,
    Double inverterTemperature,
    Double dailyYield,
    Double totalYield,
    @JsonProperty("MPPTTotalInputPower") Double mpptTotalInputPower,
    Map<String, Double> mpptMap
) {
}

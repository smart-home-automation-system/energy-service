package cloud.cholewa.energy.solaxcloud;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

//The battery as v2/device/realtime_data answers it with deviceType=2 (HAS-329): SOC and SOH %, chargeDischargePower W
//(positive while charging - +146 W with +3.1 A on 2026-11-01), voltage V, current A, temperature °C, the cycles
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudBattery(
    String deviceSn,
    String plantLocalTime,
    Double batterySOC,
    Double batterySOH,
    Double chargeDischargePower,
    Double batteryVoltage,
    Double batteryCurrent,
    Double batteryTemperature,
    Integer batteryCycleTimes,
    Double batteryRemainings
) {
}

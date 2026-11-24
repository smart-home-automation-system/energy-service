package cloud.cholewa.energy.outage;

import java.util.Map;
import java.util.Optional;

//What the grid meter says of itself in the sys component of Shelly.GetStatus (HAS-505), checked on the real Pro 3EM on
//2026-11-24: uptime - seconds since it started, a whole number; reset_reason - the reset reason of its ESP32 (1 power-on,
//3 a restart of the software, 4 - 7 a crash or a watchdog, 9 a brownout), left out by a firmware that does not give it.
//An answer without sys, or with an uptime that is not a number of 0 or more, is not the model - a failed read
record MeterSys(long uptime, Integer resetReason) {

    static final int POWER_ON = 1;

    static Optional<MeterSys> of(final Map<String, Object> status) {
        return Optional.ofNullable(status)
            .map(answer -> answer.get("sys"))
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .flatMap(sys -> Optional.ofNullable(sys.get("uptime"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::longValue)
                .filter(uptime -> uptime >= 0)
                .map(uptime -> new MeterSys(uptime, resetReasonOf(sys.get("reset_reason")))));
    }

    private static Integer resetReasonOf(final Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}

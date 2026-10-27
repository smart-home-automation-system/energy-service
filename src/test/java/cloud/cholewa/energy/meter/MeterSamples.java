package cloud.cholewa.energy.meter;

import cloud.cholewa.shelly.model.EmDataStatus;
import cloud.cholewa.shelly.model.EmStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

//The bodies of the topics of a Pro 3EM, of the shape the meter of the house publishes (HAS-327) with invented
//values, and a clock to move between them
final class MeterSamples {

    static final String METER = "shellypro3em-a1b2c3d4e5f6";
    static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    static final Instant START = Instant.parse("2026-10-27T10:00:00Z");

    static final String READINGS = """
        {"id":0,"a_current":1.652,"a_voltage":236.8,"a_act_power":-171.4,"a_aprt_power":391.2,"a_pf":0.44,
         "a_freq":50.0,"b_current":1.918,"b_voltage":235.4,"b_act_power":31.2,"b_aprt_power":451.5,"b_pf":0.07,
         "b_freq":50.0,"c_current":2.431,"c_voltage":234.6,"c_act_power":26.7,"c_aprt_power":570.3,"c_pf":0.05,
         "c_freq":49.9,"n_current":null,"total_current":6.001,"total_act_power":-113.5,"total_aprt_power":1413.0,
         "user_calibrated_phase":[]}""";

    static final String COUNTERS = """
        {"id":0,"a_total_act_energy":243011.25,"a_total_act_ret_energy":732540.5,"b_total_act_energy":398140.0,
         "b_total_act_ret_energy":694090.75,"c_total_act_energy":249860.5,"c_total_act_ret_energy":732220.25,
         "total_act":891011.75,"total_act_ret":2158851.5}""";

    private MeterSamples() {
    }

    static byte[] bytes(final String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    static EmStatus readings(final double totalActivePower) {
        return new EmStatus().id(0).aVoltage(236.8).aCurrent(1.652).aActPower(-171.4).aAprtPower(391.2).aPf(0.44)
            .aFreq(50.0).bVoltage(235.4).bActPower(31.2).cVoltage(234.6).cActPower(26.7)
            .totalActPower(totalActivePower);
    }

    static EmDataStatus counters(final double takenWh, final double returnedWh) {
        return new EmDataStatus().id(0).totalAct(takenWh).totalActRet(returnedWh);
    }

    //a clock that a test moves on
    static final class MovableClock extends Clock {

        private Instant now = START;

        void moveBy(final Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return HOUSE;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

}

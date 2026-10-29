package cloud.cholewa.energy.solax;

import cloud.cholewa.energy.registry.Inverter;
import cloud.cholewa.home.model.InverterBrand;
import cloud.cholewa.home.model.RoomName;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

//The answers of the dongle of the SOLAX recorded in the house (src/test/resources/solax, the dongle and inverter
//serials replaced by invented ones), the SOLAX of the registry and a clock to move between reads (HAS-328)
public final class SolaxSamples {

    //2026-10-27 20:49, the battery discharging at night; SolaxCloud at 20:47 for the same inverter: AC 506 / 856 /
    //456 W, 234.5 / 233.1 / 233.3 V, PF 0.95, inverter 48 °C, daily yield 38.6 kWh, total 4510.6 kWh; battery SOC 64 %,
    //SOH 99 %, -2137 W, 52.3 V, -40.7 A, 20 °C, 132 cycles; the plant charged 26.6 kWh and discharged 10.2 kWh today
    public static final String EVENING = "solax/2026-10-27-evening.json";
    //2026-10-28 10:27:30, the panels giving and the battery discharging by day; SolaxCloud at 10:27:37: MPPT1 635.8 V,
    //1.4 A, 896 W, MPPT2 269.6 V, 1.4 A, 387 W, together 1283 W; battery SOC 11 %, -1893 W
    public static final String DAY = "solax/2026-10-28-day.json";
    //2026-10-29 19:09, the battery empty and the house taking from the grid; the Pro 3EM 1846 W at the same time,
    //SolaxCloud gridPower -1667 W at 19:07
    public static final String GRID_IMPORT = "solax/2026-10-29-evening.json";
    //2026-10-23 19:52, the battery discharging at night (the samples of the planning of HAS-324)
    public static final String EARLIER_EVENING = "solax/2026-10-23-evening.json";

    public static final String SERIAL = "H1234567890ABC";
    public static final String REGISTRATION = "SABCDEFGHI";
    public static final long ID = 134;
    public static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    public static final Instant START = Instant.parse("2026-10-27T19:49:00Z");

    public static final JsonMapper JSON = JsonMapper.builder().build();

    private SolaxSamples() {
    }

    public static String sample(final String name) {
        try (InputStream in = SolaxSamples.class.getClassLoader().getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static SolaxReading reading(final String name) {
        return Type33.parse(sample(name), JSON);
    }

    public static Inverter solax(final String address) {
        return new Inverter(ID, "SOLAX inverter", RoomName.GARAGE, InverterBrand.SOLAX, SERIAL, address, REGISTRATION,
            null);
    }

    public static Inverter sofar() {
        return new Inverter(135, "SOFAR inverter", RoomName.BOILER, InverterBrand.SOFAR, "SH1234567890ABCDEFGH",
            "192.0.2.204", null, 1234567890L);
    }

    //a clock that a test moves on
    public static final class MovableClock extends Clock {

        private Instant now = START;

        public void moveBy(final Duration duration) {
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

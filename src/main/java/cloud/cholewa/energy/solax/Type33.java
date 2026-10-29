package cloud.cholewa.energy.solax;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

//The answer of the dongle of the SOLAX X3-NEO-8K-LV (Pocket WiFi+LAN, firmware 1.007.02) to ReadRealTimeData:
//{sn, ver, type: 33, Data[300], Information[10]}. Type 33 is in no public library, so the map is the one of this
//house (HAS-328), each index pinned by Type33Test on samples compared with SolaxCloud:
//[0-2] voltage per phase ÷10, [3-5] current ÷10, [6] frequency ÷100, [7] power factor ÷100, [8-10] power per
//phase W, [11] AC power W, [12-13] PV1 / PV2 voltage ÷10, [14-15] PV1 / PV2 current ÷10, [16-17] PV1 / PV2 power
//W, [18] run mode, [32-33] grid power of the meter of the inverter W, 32-bit signed, low word first, negative while
//the house takes from the grid (served the other way round), [36] battery voltage ÷10, [37] battery current ÷10 signed, [38] battery
//power W signed, [42] inverter temperature, [89] battery SOH %, [101] / [102] discharged / charged today ÷10,
//[103-104] total yield ÷10 (low word first), [105] daily yield ÷10, [125] SOC %, [127] battery temperature signed,
//[138] battery cycles. Information[2] is the serial number of the inverter. A value of Data not listed is never read.
//The dongle answers text/html, so the body is parsed here. A refused registration number is a body that is not JSON
//(0:code,message:"failed"}), an empty one the answer to a request without it
final class Type33 {

    static final int TYPE = 33;
    private static final int LAST_INDEX = 138;
    private static final int SERIAL = 2;

    private Type33() {
    }

    static SolaxReading parse(final String body, final JsonMapper jsonMapper) {
        if (body == null || body.isBlank()) {
            throw new SolaxCallException("The dongle answered without a body");
        }
        if (body.contains("\"failed\"")) {
            throw new RegistrationRefusedException();
        }
        final JsonNode root = tree(body, jsonMapper);
        if (root.path("type").asInt(-1) != TYPE) {
            throw new SolaxCallException("The dongle answered a type other than " + TYPE + ": " + root.path("type"));
        }
        final JsonNode data = root.path("Data");
        if (!data.isArray() || data.size() <= LAST_INDEX) {
            throw new SolaxCallException("The dongle answered without the Data of type " + TYPE);
        }
        final JsonNode serial = root.path("Information").path(SERIAL);
        if (!serial.isString()) {
            throw new SolaxCallException("The dongle answered without the serial number of the inverter");
        }
        return reading(new Words(data), serial.asString());
    }

    private static JsonNode tree(final String body, final JsonMapper jsonMapper) {
        try {
            return jsonMapper.readTree(body);
        } catch (JacksonException e) {
            throw new SolaxCallException("The dongle answered something that is not JSON");
        }
    }

    private static SolaxReading reading(final Words data, final String serial) {
        return new SolaxReading(
            serial,
            data.unsigned(18),
            phase(data, 0),
            phase(data, 1),
            phase(data, 2),
            data.scaled(6, 2),
            data.scaled(7, 2),
            (double) data.signed(11),
            (double) data.signed(42),
            data.scaled(105, 1),
            BigDecimal.valueOf(data.unsigned(103) + data.unsigned(104) * 65536L).movePointLeft(1).doubleValue(),
            pv(data, 0),
            pv(data, 1),
            (double) -data.signed32(32),
            new SolaxReading.Battery(
                (double) data.unsigned(125),
                (double) data.unsigned(89),
                (double) data.signed(38),
                data.scaled(36, 1),
                BigDecimal.valueOf(data.signed(37)).movePointLeft(1).doubleValue(),
                (double) data.signed(127),
                data.unsigned(138),
                data.scaled(102, 1),
                data.scaled(101, 1)));
    }

    private static SolaxReading.Phase phase(final Words data, final int phase) {
        return new SolaxReading.Phase(data.scaled(phase, 1), data.scaled(3 + phase, 1), (double) data.signed(8 + phase));
    }

    private static SolaxReading.Pv pv(final Words data, final int string) {
        return new SolaxReading.Pv(data.scaled(12 + string, 1), data.scaled(14 + string, 1),
            (double) data.unsigned(16 + string));
    }

    //the 16-bit words of Data
    private record Words(JsonNode data) {

        //a word that is not a whole number fails the read - never a 0 served as a fresh value
        int unsigned(final int index) {
            final JsonNode word = data.get(index);
            if (!word.isIntegralNumber()) {
                throw new SolaxCallException("The dongle answered Data[" + index + "] that is not a number: " + word);
            }
            return word.asInt();
        }

        int signed(final int index) {
            final int word = unsigned(index);
            return word >= 0x8000 ? word - 0x10000 : word;
        }

        //two words, the low one first, as one signed 32-bit number
        int signed32(final int lowIndex) {
            return unsigned(lowIndex) | unsigned(lowIndex + 1) << 16;
        }

        //a decimal shift, not a division: 2335 / 10 has no binary tail this way
        double scaled(final int index, final int digits) {
            return BigDecimal.valueOf(unsigned(index)).movePointLeft(digits).doubleValue();
        }
    }
}

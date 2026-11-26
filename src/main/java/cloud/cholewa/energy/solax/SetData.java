package cloud.cholewa.energy.solax;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

//The answer of the dongle of the SOLAX X3-NEO-8K-LV (Pocket WiFi+LAN, firmware 1.007.02) to ReadSetData (HAS-518): a
//bare array of 493 numbers - the settings of the inverter, in no public library and not in the Open API of the SolaX
//cloud. The map is the one of this house, each index pinned on the real inverter with the owner and by SetDataTest on
//the answers of those days:
//[71] the work mode (WorkMode: 160 Self Use, 161 Back Up, 164 Feed-in Priority) - three changes by hand, 2026-11-25 / 26
//[146] the Min SoC of Back Up %, [148] its Charge Battery To % - six writes of the reserve read back, 2026-11-25, and
//the Min SoC set by hand to 80 on 2026-11-26.
//Seen, not read: [144] follows Charge Battery To of Self Use, [151] 15127 (23:59) in Back Up and 0 in Self Use, [344]
//went from 10 to 11 with the change to Feed-in Priority (the Min SOC its screen showed) and stayed; [1] and [2] change
//with every read.
//An array of another length is another firmware or inverter: a failed read, never a guess at the same indexes
final class SetData {

    static final int LENGTH = 493;
    private static final int WORK_MODE = 71;
    private static final int BACK_UP_MIN_SOC = 146;
    private static final int BACK_UP_CHARGE_TO = 148;

    private SetData() {
    }

    static SolaxSettings parse(final String body, final JsonMapper jsonMapper) {
        if (body == null || body.isBlank()) {
            throw new SolaxCallException("The dongle answered without a body");
        }
        if (body.contains("\"failed\"")) {
            throw new RegistrationRefusedException();
        }
        final JsonNode data = tree(body, jsonMapper);
        if (!data.isArray() || data.size() != LENGTH) {
            throw new SolaxCallException("The dongle answered settings that are not the " + LENGTH
                + " numbers this service knows: " + (data.isArray() ? data.size() + " values" : "no array"));
        }
        final int workMode = word(data, WORK_MODE);
        return new SolaxSettings(workMode, WorkMode.of(workMode), percent(word(data, BACK_UP_MIN_SOC)),
            percent(word(data, BACK_UP_CHARGE_TO)));
    }

    private static JsonNode tree(final String body, final JsonMapper jsonMapper) {
        try {
            return jsonMapper.readTree(body);
        } catch (JacksonException e) {
            throw new SolaxCallException("The dongle answered something that is not JSON");
        }
    }

    //a word that is not a whole number fails the read - never a 0 served as a setting
    private static int word(final JsonNode data, final int index) {
        final JsonNode word = data.get(index);
        if (!word.isIntegralNumber()) {
            throw new SolaxCallException("The dongle answered setting [" + index + "] that is not a number: " + word);
        }
        return word.asInt();
    }

    private static Integer percent(final int value) {
        return value >= 0 && value <= 100 ? value : null;
    }
}

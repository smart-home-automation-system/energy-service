package cloud.cholewa.energy.charge;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

//The bodies of batch_set_spontaneity_self_use (HAS-336), the fields as the documentation of the SolaX Developer Platform
//gives them (read 2026-11-03): snList, businessType 1, minSoc, chargeFromGridEnable, chargeUpperSoc, the charge and
//discharge windows 1 as hh:mm, enableTimePeriod2 and the windows 2 (required while it is 1).
//The charge keeps the floor and the discharge window of the standing base, allows the grid up to the target and sets the
//window of the run; a window across midnight runs to 23:59 in period 1 and on from 00:00 in period 2 (checked on the
//first real run). The standing base (HAS-494) is the baseline the owner runs - its grid charge, no charge window
//(00:00 - 00:00), its discharge window, period 2 off - or the battery reserve: the floor and the grid charge limit at the
//level, the grid charging all day (00:00 - 23:59)
final class SelfUseCommand {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final String MIDNIGHT = "00:00";
    private static final String LAST_MINUTE = "23:59";
    private static final String PERIOD_2 = "enableTimePeriod2";

    private SelfUseCommand() {
    }

    static ObjectNode charge(final JsonMapper json, final String serial, final SelfUseBase base,
                             final int targetSoc, final LocalDateTime start, final LocalDateTime end) {
        final boolean acrossMidnight = !start.toLocalDate().equals(end.toLocalDate());
        final ObjectNode body = common(json, serial, base)
            .put("chargeFromGridEnable", 1)
            .put("chargeUpperSoc", targetSoc)
            .put("chargeStartTimePeriod1", HH_MM.format(start))
            .put("chargeEndTimePeriod1", acrossMidnight ? LAST_MINUTE : HH_MM.format(end));
        if (!acrossMidnight) {
            return body.put(PERIOD_2, 0);
        }
        return body.put(PERIOD_2, 1)
            .put("chargeStartTimePeriod2", MIDNIGHT)
            .put("chargeEndTimePeriod2", HH_MM.format(end))
            .put("dischargeStartTimePeriod2", format(base.dischargeStart()))
            .put("dischargeEndTimePeriod2", format(base.dischargeEnd()));
    }

    static ObjectNode standing(final JsonMapper json, final String serial, final SelfUseBase base) {
        return common(json, serial, base)
            .put("chargeFromGridEnable", base.chargeFromGrid() ? 1 : 0)
            .put("chargeUpperSoc", base.chargeUpperSoc())
            .put("chargeStartTimePeriod1", MIDNIGHT)
            .put("chargeEndTimePeriod1", base.chargeAllDay() ? LAST_MINUTE : MIDNIGHT)
            .put(PERIOD_2, 0);
    }

    private static ObjectNode common(final JsonMapper json, final String serial, final SelfUseBase base) {
        final ObjectNode body = json.createObjectNode();
        body.putArray("snList").add(serial);
        return body.put("businessType", 1)
            .put("minSoc", base.minSoc())
            .put("dischargeStartTimePeriod1", format(base.dischargeStart()))
            .put("dischargeEndTimePeriod1", format(base.dischargeEnd()));
    }

    private static String format(final LocalTime time) {
        return HH_MM.format(time);
    }
}

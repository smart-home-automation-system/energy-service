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
//The charge keeps the floor and the discharge window of the baseline, allows the grid up to the target and sets the
//window of the run; a window across midnight runs to 23:59 in period 1 and on from 00:00 in period 2 (checked on the
//first real run). The baseline is the Self Use the owner runs: its grid charge, no charge window (00:00 - 00:00), its
//discharge window, period 2 off
final class SelfUseCommand {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final String MIDNIGHT = "00:00";
    private static final String LAST_MINUTE = "23:59";

    private SelfUseCommand() {
    }

    static ObjectNode charge(final JsonMapper json, final String serial, final ChargeProperties.Baseline baseline,
                             final int targetSoc, final LocalDateTime start, final LocalDateTime end) {
        final boolean acrossMidnight = !start.toLocalDate().equals(end.toLocalDate());
        final ObjectNode body = common(json, serial, baseline)
            .put("chargeFromGridEnable", 1)
            .put("chargeUpperSoc", targetSoc)
            .put("chargeStartTimePeriod1", HH_MM.format(start))
            .put("chargeEndTimePeriod1", acrossMidnight ? LAST_MINUTE : HH_MM.format(end));
        if (!acrossMidnight) {
            return body.put("enableTimePeriod2", 0);
        }
        return body.put("enableTimePeriod2", 1)
            .put("chargeStartTimePeriod2", MIDNIGHT)
            .put("chargeEndTimePeriod2", HH_MM.format(end))
            .put("dischargeStartTimePeriod2", HH_MM.format(baseline.dischargeStart()))
            .put("dischargeEndTimePeriod2", HH_MM.format(baseline.dischargeEnd()));
    }

    static ObjectNode baseline(final JsonMapper json, final String serial, final ChargeProperties.Baseline baseline) {
        return common(json, serial, baseline)
            .put("chargeFromGridEnable", Boolean.TRUE.equals(baseline.chargeFromGrid()) ? 1 : 0)
            .put("chargeUpperSoc", baseline.chargeUpperSoc())
            .put("chargeStartTimePeriod1", MIDNIGHT)
            .put("chargeEndTimePeriod1", MIDNIGHT)
            .put("enableTimePeriod2", 0);
    }

    private static ObjectNode common(final JsonMapper json, final String serial,
                                     final ChargeProperties.Baseline baseline) {
        final ObjectNode body = json.createObjectNode();
        body.putArray("snList").add(serial);
        return body.put("businessType", 1)
            .put("minSoc", baseline.minSoc())
            .put("dischargeStartTimePeriod1", format(baseline.dischargeStart()))
            .put("dischargeEndTimePeriod1", format(baseline.dischargeEnd()));
    }

    private static String format(final LocalTime time) {
        return HH_MM.format(time);
    }
}

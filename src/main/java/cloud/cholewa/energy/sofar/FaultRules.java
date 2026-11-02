package cloud.cholewa.energy.sofar;

import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.time.HouseTimes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

//What the faults of the SOFAR are to the household (HAS-330), without I/O - pinned by FaultRulesTest. The cloud gives
//the faults as words (Sta_1 - Sta_9), so a fault is its words - the detail of its source_alert row, at most 200
//characters. A fault of the reading not held yet is new: one alert a pass naming every new one, since the time the
//logger collected the reading; a held fault the reading no longer has is over: one info a pass naming every one
final class FaultRules {

    static final String SOURCE = "sofar";
    static final String KIND = "alarm";
    static final int DETAIL_LENGTH = 200;

    private FaultRules() {
    }

    record Decision(List<SourceAlertEntity> started, String startedText, List<SourceAlertEntity> ended,
                    String endedText) {
    }

    static Decision decide(final SofarReading reading, final List<SourceAlertEntity> held, final LocalDateTime now) {
        final Set<String> faults = reading.faults().stream().map(FaultRules::detail).collect(Collectors.toSet());
        final Set<String> heldDetails = held.stream().map(SourceAlertEntity::detail).collect(Collectors.toSet());
        final List<SourceAlertEntity> started = faults.stream()
            .filter(fault -> !heldDetails.contains(fault))
            .sorted()
            .map(fault -> new SourceAlertEntity(null, SOURCE, KIND, reading.collectedAt(), now, null, fault))
            .toList();
        final List<SourceAlertEntity> ended = held.stream()
            .filter(row -> !faults.contains(row.detail()))
            .map(row -> new SourceAlertEntity(row.id(), row.source(), row.kind(), row.openedAt(), row.lastToldAt(), now,
                row.detail()))
            .toList();
        return new Decision(started, started(started), ended, ended(ended));
    }

    //"SOFAR fault: GridOVP; NoGrid - since 10:19."
    private static String started(final List<SourceAlertEntity> rows) {
        return rows.isEmpty() ? null : "SOFAR fault: " + rows.stream().map(SourceAlertEntity::detail)
            .collect(Collectors.joining("; ")) + " - since " + HouseTimes.hour(rows.getFirst().openedAt()) + ".";
    }

    //"SOFAR fault over: GridOVP - from 10:19 to 11:00 (41 min)."
    private static String ended(final List<SourceAlertEntity> rows) {
        return rows.isEmpty() ? null : "SOFAR fault over: " + rows.stream()
            .map(row -> row.detail() + " - from " + HouseTimes.hour(row.openedAt()) + " to "
                + HouseTimes.hour(row.closedAt()) + " (" + HouseTimes.length(row.openedAt(), row.closedAt()) + ")")
            .collect(Collectors.joining("; ")) + ".";
    }

    static String detail(final String fault) {
        return fault.length() > DETAIL_LENGTH ? fault.substring(0, DETAIL_LENGTH) : fault;
    }
}

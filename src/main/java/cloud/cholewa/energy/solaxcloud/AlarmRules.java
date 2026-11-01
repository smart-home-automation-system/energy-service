package cloud.cholewa.energy.solaxcloud;

import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.time.HouseTimes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

//What the alarms of the SOLAX in the SolaX cloud are to the household (HAS-329), without I/O - pinned by
//AlarmRulesTest. An ongoing alarm not held yet is new: one alert a pass naming every new one; a held alarm the cloud no
//longer lists is over: one info a pass naming every one. An alarm is its error code, its start and its name (detail of
//source_alert), so the same error later is another alarm
final class AlarmRules {

    static final String SOURCE = "solax";
    static final String KIND = "alarm";
    private static final String SEPARATOR = "|";

    private AlarmRules() {
    }

    record Decision(List<SourceAlertEntity> started, String startedText, List<SourceAlertEntity> ended,
                    String endedText) {
    }

    static Decision decide(final List<CloudAlarm> ongoing, final List<SourceAlertEntity> held, final LocalDateTime now) {
        final Set<String> heldDetails = held.stream().map(SourceAlertEntity::detail).collect(Collectors.toSet());
        final List<CloudAlarm> fresh = ongoing.stream()
            .filter(alarm -> !heldDetails.contains(detail(alarm)))
            .toList();
        final Set<String> ongoingDetails = ongoing.stream().map(AlarmRules::detail).collect(Collectors.toSet());
        final List<SourceAlertEntity> ended = held.stream()
            .filter(row -> !ongoingDetails.contains(row.detail()))
            .map(row -> new SourceAlertEntity(row.id(), row.source(), row.kind(), row.openedAt(), row.lastToldAt(), now,
                row.detail()))
            .toList();
        final List<SourceAlertEntity> started = fresh.stream()
            .map(alarm -> new SourceAlertEntity(null, SOURCE, KIND, startOf(alarm, now), now, null, detail(alarm)))
            .toList();
        return new Decision(started, started(fresh, now), ended, ended(ended));
    }

    //"SOLAX alarm: Bat Power Low (error 17, type 1, level 2) since 10:12."
    private static String started(final List<CloudAlarm> alarms, final LocalDateTime now) {
        return alarms.isEmpty() ? null : "SOLAX alarm: " + alarms.stream()
            .map(alarm -> alarm.alarmName() + " (error " + alarm.errorCode() + ", type " + alarm.alarmType() + ", level "
                + alarm.alarmLevel() + ") since " + HouseTimes.hour(startOf(alarm, now)))
            .collect(Collectors.joining("; ")) + ".";
    }

    //"SOLAX alarm over: Bat Power Low (error 17) - from 10:12 to 11:23 (1 h 11 min)."
    private static String ended(final List<SourceAlertEntity> rows) {
        return rows.isEmpty() ? null : "SOLAX alarm over: " + rows.stream()
            .map(row -> nameOf(row.detail()) + " (error " + codeOf(row.detail()) + ") - from "
                + HouseTimes.hour(row.openedAt()) + " to " + HouseTimes.hour(row.closedAt()) + " ("
                + HouseTimes.length(row.openedAt(), row.closedAt()) + ")")
            .collect(Collectors.joining("; ")) + ".";
    }

    static String detail(final CloudAlarm alarm) {
        return alarm.errorCode() + SEPARATOR + alarm.alarmStartTime() + SEPARATOR + alarm.alarmName();
    }

    private static LocalDateTime startOf(final CloudAlarm alarm, final LocalDateTime now) {
        return Optional.ofNullable(SolaxCloudState.plantTime(alarm.alarmStartTime())).orElse(now);
    }

    private static String codeOf(final String detail) {
        return detail.split("\\|", -1)[0];
    }

    private static String nameOf(final String detail) {
        final String[] parts = detail.split("\\|", 3);
        return parts.length == 3 ? parts[2] : detail;
    }
}

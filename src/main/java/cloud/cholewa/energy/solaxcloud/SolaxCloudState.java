package cloud.cholewa.energy.solaxcloud;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

//What the service holds of the SolaX cloud, in memory (HAS-329): the last answers of the inverter and its battery, the
//house time of the last read and whether it succeeded. Everything is null until the first read; a failed read keeps
//the last answers
public record SolaxCloudState(
    CloudInverter inverter,
    CloudBattery battery,
    LocalDateTime readAt,
    Boolean reachable
) {

    static final SolaxCloudState UNKNOWN = new SolaxCloudState(null, null, null, null);
    private static final DateTimeFormatter PLANT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    //a read of both, or of one when the other failed: what failed keeps its last answer, and the cloud is reachable only
    //when both answered
    SolaxCloudState withRead(final CloudInverter newInverter, final CloudBattery newBattery, final LocalDateTime at) {
        return new SolaxCloudState(Optional.ofNullable(newInverter).orElse(inverter),
            Optional.ofNullable(newBattery).orElse(battery), at, newInverter != null && newBattery != null);
    }

    SolaxCloudState unreachable() {
        return new SolaxCloudState(inverter, battery, readAt, false);
    }

    //the time of the plant of an answer, the house time - null when it has none or not in its form
    public static LocalDateTime plantTime(final String value) {
        try {
            return Optional.ofNullable(value).map(text -> LocalDateTime.parse(text, PLANT_TIME)).orElse(null);
        } catch (final DateTimeParseException notPlantTime) {
            return null;
        }
    }

    //an answer that may stand for the dongle: the cloud has it, it is not older than the bound and newer than the last
    //reading of the dongle (null when there is none)
    public boolean fresh(final String plantLocalTime, final LocalDateTime now, final Duration staleAfter,
                         final LocalDateTime dongleReadingAt) {
        return Optional.ofNullable(plantTime(plantLocalTime))
            .filter(at -> !at.isBefore(now.minus(staleAfter)))
            .filter(at -> dongleReadingAt == null || at.isAfter(dongleReadingAt))
            .isPresent();
    }
}

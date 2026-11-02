package cloud.cholewa.energy.solaxcloud;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

//The energy of a day as the SolaX cloud counts it (HAS-331), the cross-check of a closed day: the yield of the SOLAX (the
//inverter's dailyYield, the same counter as its dongle) and the energy its meter counted from and to the grid (the plant's
//dailyImported / dailyExported). The last answer of each day by the time of the plant - written by SolaxCloudReader every
//five minutes, so the last one of a day is at most five minutes before its end. In memory: a restart forgets them, and a
//day closed after it has no cloud columns. The last DAYS_KEPT days are kept
@Component
public class CloudDays {

    static final int DAYS_KEPT = 3;

    private final Map<LocalDate, CloudDay> days = new ConcurrentHashMap<>();

    //what the cloud counted for a day so far, kWh; a value not read is null
    public record CloudDay(Double dailyYield, Double imported, Double exported) {
    }

    public Optional<CloudDay> of(final LocalDate day) {
        return Optional.ofNullable(days.get(day));
    }

    void inverter(final CloudInverter answer) {
        Optional.ofNullable(SolaxCloudState.plantTime(answer.plantLocalTime())).map(LocalDateTime::toLocalDate)
            .ifPresent(day -> {
                days.merge(day, new CloudDay(answer.dailyYield(), null, null),
                    (held, read) -> new CloudDay(read.dailyYield(), held.imported(), held.exported()));
                forgetBefore(day);
            });
    }

    void plant(final CloudPlant answer) {
        Optional.ofNullable(SolaxCloudState.plantTime(answer.plantLocalTime())).map(LocalDateTime::toLocalDate)
            .ifPresent(day -> {
                days.merge(day, new CloudDay(null, answer.dailyImported(), answer.dailyExported()),
                    (held, read) -> new CloudDay(held.dailyYield(), read.imported(), read.exported()));
                forgetBefore(day);
            });
    }

    private void forgetBefore(final LocalDate day) {
        days.keySet().removeIf(held -> held.isBefore(day.minusDays(DAYS_KEPT - 1L)));
    }
}

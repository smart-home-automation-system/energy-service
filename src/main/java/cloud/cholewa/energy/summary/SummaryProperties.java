package cloud.cholewa.energy.summary;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.LocalTime;

//The daily energy summary (HAS-339): time - the house time it is sent at, a whole minute ("21:30"); the watch looks every
//minute, so seconds would never be met to the second and are refused at the start
@Validated
@ConfigurationProperties(prefix = "energy.summary")
public record SummaryProperties(
    @NotNull LocalTime time
) {

    @AssertTrue(message = "energy.summary.time must be a whole minute")
    boolean isWholeMinute() {
        return time == null || time.getSecond() == 0 && time.getNano() == 0;
    }
}

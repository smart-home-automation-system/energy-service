package cloud.cholewa.energy.history;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

//How long the readings of the grid meter, the inverters and the battery are kept (HAS-331) - older rows are deleted
//every night by the retention pass, the closed days are kept forever. A bare number is days - without the unit it would
//bind as milliseconds and the first night would delete the whole history, hence the lower bound of 31 days, the longest
//range of the history. The upper bound of ten years keeps a value meant in seconds from keeping everything forever.
//Pinned by RetentionPropertiesTest
@Validated
@ConfigurationProperties(prefix = "energy.history")
public record RetentionProperties(
    @NotNull @DurationUnit(ChronoUnit.DAYS) @DurationMin(days = 31) @DurationMax(days = 3650) Duration retention
) {
}

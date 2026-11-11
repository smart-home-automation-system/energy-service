package cloud.cholewa.energy.elicznik;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * The eLicznik portal of Tauron Dystrybucja (HAS-340): no official API, the login of its web pages.
 *
 * @param loginUrl     the login of Tauron Dystrybucja, asked with {@code ?service=} the portal
 * @param serviceUrl   the portal
 * @param username     the account of the owner - from the secret, never logged
 * @param password     its password - from the secret, never logged
 * @param meterId      the point of the meter as the portal names it ({@code site[client]}, three numbers joined by
 *                     {@code _}) - from the manifest
 * @param retryDays    how many days back a day the portal has not served whole is asked again (1 - 31); older is given up
 * @param alertAfter   failed mornings for this long are an alert: the portal changed or the password expired (a bare
 *                     number is days, 1 - 14)
 * @param warnAbove    a day whose import or export differs from the 3EM by more than this share is a WARN (0.01 - 0.5)
 * @param fetchEvery   the least time between two logins asked by hand (POST /elicznik/fetch, 10 min - 24 h)
 */
@Validated
@ConfigurationProperties(prefix = "energy.elicznik")
public record ElicznikProperties(
    @NotNull URI loginUrl,
    @NotNull URI serviceUrl,
    @NotBlank String username,
    @NotBlank String password,
    @NotNull @Pattern(regexp = "\\d+_\\d+_\\d+") String meterId,
    @NotNull @Min(1) @Max(31) Integer retryDays,
    @NotNull @DurationUnit(ChronoUnit.DAYS) @DurationMin(days = 1) @DurationMax(days = 14) Duration alertAfter,
    @NotNull @DecimalMin("0.01") @DecimalMax("0.5") BigDecimal warnAbove,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 10) @DurationMax(hours = 24) Duration fetchEvery
) {

    //a record prints its components: the account and its password would reach a log of the binding
    @Override
    public String toString() {
        return "ElicznikProperties[loginUrl=" + loginUrl + ", serviceUrl=" + serviceUrl + ", username=***, password=***"
            + ", meterId=***, retryDays=" + retryDays + ", alertAfter=" + alertAfter + ", warnAbove=" + warnAbove
            + ", fetchEvery=" + fetchEvery + "]";
    }
}

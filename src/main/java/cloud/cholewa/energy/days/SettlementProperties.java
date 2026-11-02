package cloud.cholewa.energy.days;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

//The settlement of the house with Tauron (HAS-331) - net-metering, the Polish "system opustów", not net-billing (owner,
//2026-10-23): the exported kWh are credited at the ratio of the agreement and may be drawn back from the grid within 12
//months, so the settlement is in kWh, never in money.
//ratio - the share of an exported kWh credited (0.7, the owner's agreement, confirmed 2026-10-23)
//periodStart - the first month of the billing year, 1 - 12 (9: the installation started in September 2020; owner,
//2026-11-02 - Tauron invoices monthly, the billing year still counts from the start)
@Validated
@ConfigurationProperties(prefix = "energy.settlement")
public record SettlementProperties(
    @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") BigDecimal ratio,
    @Min(1) @Max(12) int periodStart
) {
}

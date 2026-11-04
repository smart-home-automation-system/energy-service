package cloud.cholewa.energy.api.model;

import cloud.cholewa.energy.forecast.ForecastCategory;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

//GET /home/energy/forecast (HAS-372) - the contract of the dashboard: will the solar work in the coming days. House
//times; kWh, kW and % rounded; a value not known is left out, stale is always written.
//fetchedAt - the older of the last fetches of both planes; stale - no fetch of one of them, or older than
//energy.forecast.stale-after
//houseLoadKwh - the mean house load of the last closed days, houseLoadDays - how many it is the mean of
//performanceRatio - per inverter the share of the potential of its panels it is taken to produce, with the days it was
//calibrated on (0: the initial ratio)
//days - seven days from today: the kWh per inverter and both, the coverage of the house load, its category, the mean
//cloud cover while the sun is up and the hours above SUNSHINE_IRRADIANCE W/m2 on the ground
//past - the last two closed days calibrated: what the forecast gave (at the ratio in use now) and what was produced
//hourly - the next 48 hours from the hour now: the expected mean power per inverter and the cloud cover
//outlook - the first fair or good day and the days before it
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ForecastReply(
    LocalDateTime fetchedAt,
    boolean stale,
    Double houseLoadKwh,
    Integer houseLoadDays,
    Ratios performanceRatio,
    List<Day> days,
    List<Past> past,
    List<Hour> hourly,
    Outlook outlook
) {

    public record Ratios(Ratio solax, Ratio sofar) {
    }

    public record Ratio(double ratio, int calibratedOnDays) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Day(
        LocalDate day,
        Double solaxKwh,
        Double sofarKwh,
        Double totalKwh,
        Double coverage,
        ForecastCategory category,
        Double cloudCover,
        Integer sunshineHours
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Past(LocalDate day, Compared solax, Compared sofar) {
    }

    public record Compared(double forecastKwh, double actualKwh) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Hour(LocalDateTime hour, Double solaxKw, Double sofarKw, Double cloudCover) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Outlook(LocalDate sunBackOn, Integer daysWithoutSun) {
    }
}

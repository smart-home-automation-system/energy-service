package cloud.cholewa.energy.summary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Optional;

//The daily energy summary (HAS-339), without I/O - its words are pinned by DaySummaryTest. kWh unless named; a value
//null is not known and is said so, never written as 0. solaxSilent / meterSilent - the source was silent for most of
//the day so far (SummaryService): the values it gives are then said to have no readings instead of a number.
//The month: the closed days of the month and today when its grid is known - credited is exported x the ratio of the
//agreement, the balance credited less imported; the credit of earlier months is the dashboard's (GET /months)
public record DaySummary(
    LocalDateTime at,
    BigDecimal solax,
    BigDecimal sofar,
    BigDecimal production,
    Peak peak,
    BigDecimal houseLoad,
    BigDecimal selfConsumption,
    BigDecimal imported,
    BigDecimal exported,
    BigDecimal charged,
    BigDecimal discharged,
    BigDecimal soc,
    Month month,
    boolean solaxSilent,
    boolean meterSilent
) {

    static final String NOT_KNOWN = "not known";
    static final String SILENT = "no readings for most of the day";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    //the hour of the house with the highest average PV power of both inverters, W
    public record Peak(LocalTime hour, BigDecimal averageWatts) {
    }

    //withToday - today is counted in the month; ratio - the share of an exported kWh credited
    public record Month(YearMonth month, BigDecimal imported, BigDecimal exported, BigDecimal ratio,
                        boolean withToday) {

        BigDecimal credited() {
            return exported.multiply(ratio);
        }

        BigDecimal balance() {
            return credited().subtract(imported);
        }
    }

    public String text() {
        return String.join("\n",
            "Energy on " + day() + " (until " + TIME.format(at) + ")",
            productionLine(),
            "House load " + meterValue(houseLoad) + ", self-consumption " + meterShare(selfConsumption),
            meterSilent ? "Grid: the grid meter had " + SILENT
                : "Grid: imported " + kwh(imported) + ", exported " + kwh(exported),
            batteryLine(),
            monthLine());
    }

    private String day() {
        return at.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + at.getDayOfMonth() + " "
            + at.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private String productionLine() {
        final String parts = "SOLAX " + (solaxSilent ? SILENT : number(solax)) + ", SOFAR " + number(sofar);
        final String total = solaxSilent || production == null ? NOT_KNOWN : kwh(production);
        final String most = Optional.ofNullable(peak)
            .map(highest -> "; most between " + TIME.format(highest.hour()) + " and "
                + TIME.format(highest.hour().plusHours(1)) + " (" + kilowatts(highest.averageWatts()) + " on average)")
            .orElse("");
        return "Production " + total + " - " + parts + most;
    }

    private String batteryLine() {
        final String now = Optional.ofNullable(soc).map(value -> ", now " + whole(value) + " %").orElse(", now "
            + NOT_KNOWN);
        return solaxSilent ? "Battery: the SOLAX had " + SILENT + now
            : "Battery: charged " + kwh(charged) + ", discharged " + kwh(discharged) + now;
    }

    private String monthLine() {
        if (month == null) {
            return "The month: " + NOT_KNOWN;
        }
        final String name = month.month().getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return name + (month.withToday() ? " so far" : " up to yesterday") + ": credited " + kwh(month.credited())
            + " (" + month.ratio().stripTrailingZeros().toPlainString() + " x " + number(month.exported())
            + " exported), imported " + kwh(month.imported()) + " - balance " + signed(month.balance()) + " kWh";
    }

    private String meterValue(final BigDecimal value) {
        return meterSilent ? NOT_KNOWN : kwh(value);
    }

    private String meterShare(final BigDecimal share) {
        return meterSilent || share == null ? NOT_KNOWN : whole(share.movePointRight(2)) + " %";
    }

    private static String kwh(final BigDecimal value) {
        return value == null ? NOT_KNOWN : number(value) + " kWh";
    }

    //one decimal; "not known" for null
    static String number(final BigDecimal value) {
        return value == null ? NOT_KNOWN : value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String signed(final BigDecimal value) {
        final String number = number(value);
        return value.signum() > 0 && !"0.0".equals(number) ? "+" + number : number;
    }

    private static String whole(final BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String kilowatts(final BigDecimal watts) {
        return number(watts.movePointLeft(3)) + " kW";
    }
}

package cloud.cholewa.energy.elicznik;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

//What the eLicznik portal answers (HAS-340), without I/O - pinned by ElicznikAnswersTest on the answers of 2026-11-10
//(src/test/resources/elicznik/). Read from allData, never from values: values sums the two hours of the night the clocks
//go back into one and leaves a hole for the hour they go forward
final class ElicznikAnswers {

    private static final DateTimeFormatter READING_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.ROOT);
    private static final String REPEATED = "T";

    private ElicznikAnswers() {
    }

    //an hour of the portal: hour 1-24 is the hour ending at it, repeated the first 02:00 - 03:00 of the night the clocks go
    //back; kwh null while the portal does not know it yet (a day not over, a meter not read)
    record Hour(int hour, boolean repeated, BigDecimal kwh) {
    }

    //a register of the meter at the end of a day, whole kWh
    record Register(LocalDate day, BigDecimal kwh) {
    }

    //the hours of POST /energia/api, in the order of the answer
    static List<Hour> hours(final JsonMapper jsonMapper, final String body) {
        final JsonNode rows = data(json(jsonMapper, body)).path("allData");
        if (!rows.isArray()) {
            throw new ElicznikException("The eLicznik portal answered the energy without its hours");
        }
        final List<Hour> hours = new ArrayList<>();
        for (final JsonNode row : rows) {
            final int hour = number(row.path("Hour"))
                .map(BigDecimal::intValueExact)
                .filter(value -> value >= 1 && value <= 24)
                .orElseThrow(() -> new ElicznikException("The eLicznik portal answered an hour that is not one"));
            final boolean known = !row.path("Status").isNull() && !row.path("Status").isMissingNode();
            final BigDecimal kwh = known ? number(row.path("EC"))
                .filter(value -> value.signum() >= 0)
                .orElseThrow(() -> new ElicznikException("The eLicznik portal answered an energy that is not one"))
                : null;
            hours.add(new Hour(hour, REPEATED.equals(row.path("Extra").asString(null)), kwh));
        }
        return hours;
    }

    //the registers of POST /odczyty/api, one a day. A register the portal has not read yet is answered without success and
    //with an empty list (HAS-422: the day before at 06:00 on 2026-11-12, served by 09:48) - none yet, never a failure;
    //anything else without success still is
    static List<Register> registers(final JsonMapper jsonMapper, final String body) {
        final JsonNode answer = json(jsonMapper, body);
        if (isNotReadYet(answer)) {
            return List.of();
        }
        final JsonNode rows = data(answer);
        if (!rows.isArray()) {
            throw new ElicznikException("The eLicznik portal answered the readings without a list");
        }
        final List<Register> registers = new ArrayList<>();
        for (final JsonNode row : rows) {
            final LocalDate day = date(row.path("Date").asString(""));
            final BigDecimal kwh = number(row.path("C"))
                .filter(value -> value.signum() >= 0)
                .orElseThrow(() -> new ElicznikException("The eLicznik portal answered a reading that is not one"));
            registers.add(new Register(day, kwh));
        }
        return registers;
    }

    private static JsonNode json(final JsonMapper jsonMapper, final String body) {
        try {
            return jsonMapper.readTree(body);
        } catch (final RuntimeException notJson) {
            throw new ElicznikException("The eLicznik portal answered something that is not JSON");
        }
    }

    //{"success":false,"data":[]}
    private static boolean isNotReadYet(final JsonNode answer) {
        final JsonNode data = answer.path("data");
        return !answer.path("success").asBoolean(false) && data.isArray() && data.isEmpty();
    }

    private static JsonNode data(final JsonNode answer) {
        if (!answer.path("success").asBoolean(false)) {
            throw new ElicznikException("The eLicznik portal answered without success");
        }
        return answer.path("data");
    }

    //"1.558" or 2587; anything else is not a number
    private static Optional<BigDecimal> number(final JsonNode value) {
        if (value.isNumber()) {
            return Optional.of(value.decimalValue());
        }
        try {
            return Optional.ofNullable(value.asString(null)).map(String::trim).map(BigDecimal::new);
        } catch (final NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    private static LocalDate date(final String text) {
        try {
            return LocalDate.parse(text, READING_DATE);
        } catch (final DateTimeParseException notADate) {
            throw new ElicznikException("The eLicznik portal answered a reading without its date");
        }
    }
}

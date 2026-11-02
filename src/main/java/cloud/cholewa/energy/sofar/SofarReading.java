package cloud.cholewa.energy.sofar;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

//One reading of the SOFAR HYD 5KTL-3PH as the Solarman cloud answers device/v1.0/currentData (HAS-330): the keys of its
//dataList read on 2026-11-02 (src/test/resources/solarman, compared with Solarman Smart):
//DP1 / DP2, DV1 / DV2, DC1 / DC2 - each string of panels W, V, A; PVTP - the panels together W; T_AC_OP - the AC side W;
//AP1-3, AV1-3, AC1-3 - each phase W, V, A; A_Fo1 - the frequency Hz; INV_ST1 - the state of the inverter in the words of
//the cloud ("Grid connected"); Sta_1 - Sta_9 - the faults in words, empty when none; T_MDU1 - the temperature of the power
//module °C; Etdy_ge1 / Et_ge0 - the production today / in total kWh. collectedAt - the house time the logger collected
//it (collectionTime, epoch seconds). A value missing or not a number is null; the battery keys (the HYD has none) and the
//inverter's own grid counters (not the balance of the house - the Pro 3EM's) are not read
public record SofarReading(
    String serialNumber,
    LocalDateTime collectedAt,
    Double pvPower,
    Pv pv1,
    Pv pv2,
    Double acPower,
    Phase a,
    Phase b,
    Phase c,
    Double frequency,
    String state,
    List<String> faults,
    Double temperature,
    Double dailyYield,
    Double totalYield
) {

    public record Pv(Double voltage, Double current, Double power) {
    }

    public record Phase(Double voltage, Double current, Double power) {
    }

    //the reading of an answer for the inverter of this serial number; an answer of another device, or without its time
    //or its data, is no reading
    static SofarReading of(final JsonNode answer, final String serialNumber, final ZoneId house) {
        if (!serialNumber.equals(answer.path("deviceSn").asString(""))) {
            throw new SolarmanException("The Solarman cloud answered for another device");
        }
        final long collectionTime = answer.path("collectionTime").asLong(0);
        if (collectionTime <= 0 || !answer.path("dataList").isArray()) {
            throw new SolarmanException("The Solarman cloud answered no data of the SOFAR");
        }
        final Map<String, String> values = new HashMap<>();
        answer.path("dataList").valueStream().forEach(entry ->
            values.put(entry.path("key").asString(""), entry.path("value").asString(null)));
        return new SofarReading(
            serialNumber,
            LocalDateTime.ofInstant(Instant.ofEpochSecond(collectionTime), house),
            number(values, "PVTP"),
            new Pv(number(values, "DV1"), number(values, "DC1"), number(values, "DP1")),
            new Pv(number(values, "DV2"), number(values, "DC2"), number(values, "DP2")),
            number(values, "T_AC_OP"),
            new Phase(number(values, "AV1"), number(values, "AC1"), number(values, "AP1")),
            new Phase(number(values, "AV2"), number(values, "AC2"), number(values, "AP2")),
            new Phase(number(values, "AV3"), number(values, "AC3"), number(values, "AP3")),
            number(values, "A_Fo1"),
            text(values, "INV_ST1").orElse(null),
            IntStream.rangeClosed(1, 9)
                .mapToObj(index -> text(values, "Sta_" + index))
                .flatMap(Optional::stream)
                .distinct()
                .toList(),
            number(values, "T_MDU1"),
            number(values, "Etdy_ge1"),
            number(values, "Et_ge0"));
    }

    private static Double number(final Map<String, String> values, final String key) {
        return text(values, key).flatMap(SofarReading::parsed).orElse(null);
    }

    private static Optional<Double> parsed(final String value) {
        try {
            return Optional.of(Double.valueOf(value));
        } catch (final NumberFormatException notNumber) {
            return Optional.empty();
        }
    }

    private static Optional<String> text(final Map<String, String> values, final String key) {
        return Optional.ofNullable(values.get(key)).map(String::trim).filter(value -> !value.isEmpty());
    }
}

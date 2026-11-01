package cloud.cholewa.energy.solaxcloud;

import cloud.cholewa.energy.solax.SolaxSamples;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

//Answers of the SolaX cloud recorded on 2026-11-01 at 12:52 for the house (HAS-329), the serials of the inverter and the
//battery, the registration number and the plant invented: the inverter and its battery, the plant, no ongoing alarm and
//two closed ones (Bat Power Low, error 17)
public final class CloudSamples {

    public static final String PLANT = "1000000000000000000";
    public static final SolaxCloudProperties PROPERTIES = properties(URI.create("http://localhost:1"));

    private CloudSamples() {
    }

    public static SolaxCloudProperties properties(final URI baseUrl) {
        return new SolaxCloudProperties(baseUrl, "client", "secret", PLANT, Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofMinutes(15));
    }

    public static String sample(final String name) {
        try (InputStream stream = Objects.requireNonNull(
            CloudSamples.class.getResourceAsStream("/solaxcloud/" + name + ".json"), name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public static CloudInverter inverter() {
        return SolaxSamples.JSON.treeToValue(result("inverter").get(0), CloudInverter.class);
    }

    public static CloudBattery battery() {
        return SolaxSamples.JSON.treeToValue(result("battery").get(0), CloudBattery.class);
    }

    //the recorded answer as if the plant had read it at this time ("yyyy-MM-dd HH:mm:ss")
    public static CloudInverter inverterAt(final String plantLocalTime) {
        return SolaxSamples.JSON.treeToValue(SolaxSamples.JSON.readTree(sample("inverter")
            .replace("2026-11-01 12:52:37", plantLocalTime)).path("result").get(0), CloudInverter.class);
    }

    public static CloudBattery batteryAt(final String plantLocalTime) {
        return SolaxSamples.JSON.treeToValue(SolaxSamples.JSON.readTree(sample("battery")
            .replace("2026-11-01 12:52:37", plantLocalTime)).path("result").get(0), CloudBattery.class);
    }

    public static CloudAlarm.Page alarms(final String name) {
        return SolaxSamples.JSON.treeToValue(result(name), CloudAlarm.Page.class);
    }

    private static JsonNode result(final String name) {
        return SolaxSamples.JSON.readTree(sample(name)).path("result");
    }
}

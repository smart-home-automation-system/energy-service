package cloud.cholewa.energy.sofar;

import cloud.cholewa.energy.solax.SolaxSamples;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

//The answer of the Solarman cloud recorded on 2026-11-02 at 10:19 for the SOFAR of the house (HAS-330), its serial and
//device id invented (the serial of SolaxSamples.sofar()): the panels giving 200 W in the morning, grid connected, no fault
public final class SofarSamples {

    public static final String MORNING = "solarman/2026-11-02-morning.json";
    public static final String SERIAL = "SH1234567890ABCDEFGH";
    //collectionTime 1793611158 in the house
    public static final LocalDateTime COLLECTED_AT = LocalDateTime.of(2026, 11, 2, 10, 19, 18);
    public static final SolarmanProperties PROPERTIES = properties(URI.create("http://localhost:1"));

    private SofarSamples() {
    }

    public static SolarmanProperties properties(final URI baseUrl) {
        return new SolarmanProperties(baseUrl, "app", "secret", "owner@example.com",
            "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8", Duration.ofMinutes(5),
            Duration.ofMinutes(15));
    }

    public static String sample() {
        return SolaxSamples.sample(MORNING);
    }

    public static JsonNode answer() {
        return SolaxSamples.JSON.readTree(sample());
    }

    public static SofarReading reading() {
        return SofarReading.of(answer(), SERIAL, SolaxSamples.HOUSE);
    }

    //the recorded reading with these faults
    public static SofarReading withFaults(final List<String> faults) {
        final SofarReading reading = reading();
        return new SofarReading(reading.serialNumber(), reading.collectedAt(), reading.pvPower(), reading.pv1(),
            reading.pv2(), reading.acPower(), reading.a(), reading.b(), reading.c(), reading.frequency(), reading.state(),
            faults, reading.temperature(), reading.dailyYield(), reading.totalYield());
    }

    public static SofarState state(final SofarReading reading, final boolean reachable) {
        return new SofarState(135L, reading, COLLECTED_AT, reachable);
    }
}

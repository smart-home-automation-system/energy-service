package cloud.cholewa.energy.elicznik;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

//The answers of the eLicznik portal of 2026-11-10 (src/test/resources/elicznik/, HAS-340): read in the owner's browser,
//rebuilt with every key of allData as answered - no meter id in any of them
final class ElicznikSamples {

    static final String CONSUM_2026_11_08 = "energy-2026-11-08-consum.json";
    static final String OZE_2026_11_08 = "energy-2026-11-08-oze.json";
    static final String CONSUM_2026_10_25 = "energy-2026-10-25-consum.json";
    static final String OZE_2026_10_25 = "energy-2026-10-25-oze.json";
    static final String CONSUM_2026_03_29 = "energy-2026-03-29-consum.json";
    static final String CONSUM_2026_11_10 = "energy-2026-11-10-consum.json";
    static final String READINGS_IMPORTED = "readings-imported.json";
    static final String READINGS_EXPORTED = "readings-exported.json";

    private ElicznikSamples() {
    }

    static String read(final String name) {
        try (InputStream in = ElicznikSamples.class.getResourceAsStream("/elicznik/" + name)) {
            if (in == null) {
                throw new IllegalStateException("No sample " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}

package cloud.cholewa.energy.monitor;

import java.util.Arrays;
import java.util.Optional;

//What the monitor of the sources watches (HAS-332), with the names source_alert keeps: the SOLAX dongle and the grid
//meter for silence, each phase of the grid meter for an overload. The SOFAR joins with HAS-330
enum Source {

    SOLAX("solax", Kind.SILENT, "the SOLAX dongle"),
    METER("meter", Kind.SILENT, "the grid meter"),
    PHASE_A("phase a", Kind.OVERLOAD, "Phase A"),
    PHASE_B("phase b", Kind.OVERLOAD, "Phase B"),
    PHASE_C("phase c", Kind.OVERLOAD, "Phase C");

    private final String wireName;
    private final Kind kind;
    private final String words;

    Source(final String wireName, final Kind kind, final String words) {
        this.wireName = wireName;
        this.kind = kind;
        this.words = words;
    }

    String wireName() {
        return wireName;
    }

    Kind kind() {
        return kind;
    }

    String words() {
        return words;
    }

    static Optional<Source> of(final String wireName, final String kind) {
        return Arrays.stream(values())
            .filter(source -> source.wireName.equals(wireName) && source.kind.wireName().equals(kind))
            .findFirst();
    }

    enum Kind {
        SILENT("silent"), OVERLOAD("overload");

        private final String wireName;

        Kind(final String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }
}

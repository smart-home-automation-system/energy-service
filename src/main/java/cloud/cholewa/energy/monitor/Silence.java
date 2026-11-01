package cloud.cholewa.energy.monitor;

import java.time.LocalDateTime;

//What a pass makes of a source that may be silent (HAS-332).
//OUT - silent for its bound, judged by whole passes: the last call failed and nothing was heard since heardAt (the
//start of the service when never). HEARD - heard at heardAt; it is a return only when that is after the episode
//opened. NOT_KNOWN - neither yet: not called since the start, or failing for less than the bound
record Silence(Status status, LocalDateTime heardAt) {

    static final Silence NOT_KNOWN = new Silence(Status.NOT_KNOWN, null);

    static Silence out(final LocalDateTime heardAt) {
        return new Silence(Status.OUT, heardAt);
    }

    static Silence heard(final LocalDateTime heardAt) {
        return new Silence(Status.HEARD, heardAt);
    }

    enum Status {
        OUT, HEARD, NOT_KNOWN
    }
}
